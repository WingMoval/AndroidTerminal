import java.util.*;

public class NativeParserSpike {
    static int parserState = 0;
    static final int GROUND=0, ESCAPE=1, CSI_ENTRY=2, CSI_PARAM=3, CSI_IGNORE=4,
                     OSC_STRING=5, OSC_ESC=6, DCS_PASSTHROUGH=7, DCS_ESC=8, RECOVERY=9;

    static int cursorRow=0, cursorCol=0, rows=24, cols=80;
    static boolean altScreen=false, cursorVisible=true, autoWrap=true, appCursorKeys=false;
    static int fgColor=0, bgColor=0;
    static boolean bold=false, underline=false, inverse=false;

    static int[] csiParams = new int[16];
    static int csiParamCount = 0;
    static int csiCurrentParam = -1; // -1 = not started, >=0 = accumulating
    static char csiPrivate = 0;
    static StringBuilder oscBuf = new StringBuilder();
    static StringBuilder dcsBuf = new StringBuilder();
    static int utf8Remaining=0, utf8CodePoint=0, utf8Accum=0;

    static void resetState() {
        cursorRow=0; cursorCol=0; altScreen=false; cursorVisible=true;
        autoWrap=true; appCursorKeys=false; fgColor=0; bgColor=0;
        bold=false; underline=false; inverse=false;
        // Reset parser state (separate from terminal state!)
        parserState=GROUND; csiParamCount=0; csiCurrentParam=-1; csiPrivate=0;
        oscBuf.setLength(0); dcsBuf.setLength(0);
        utf8Remaining=0; utf8CodePoint=0; utf8Accum=0;
    }

    static void feed(byte[] data) { for (byte b : data) processByte(b & 0xFF); }

    static void processByte(int byteVal) {
        if (utf8Remaining > 0) {
            if ((byteVal & 0xC0) == 0x80) {
                utf8CodePoint = (utf8CodePoint << 6) | (byteVal & 0x3F);
                utf8Accum++;
                if (utf8Accum == utf8Remaining) { utf8Remaining=0; processCodePoint(utf8CodePoint); }
                return;
            } else { processCodePoint(0xFFFD); utf8Remaining=0; }
        }
        if (byteVal < 0x80) { processCodePoint(byteVal); return; }
        if ((byteVal & 0xE0) == 0xC0) { utf8Remaining=1; utf8CodePoint=byteVal&0x1F; utf8Accum=0; }
        else if ((byteVal & 0xF0) == 0xE0) { utf8Remaining=2; utf8CodePoint=byteVal&0x0F; utf8Accum=0; }
        else if ((byteVal & 0xF8) == 0xF0) { utf8Remaining=3; utf8CodePoint=byteVal&0x07; utf8Accum=0; }
        else { processCodePoint(0xFFFD); }
    }

    static void processCodePoint(int cp) {
        switch (parserState) {
            case GROUND: handleGround(cp); break;
            case ESCAPE: handleEscape(cp); break;
            case CSI_ENTRY: case CSI_PARAM: case CSI_IGNORE: handleCsi(cp); break;
            case OSC_STRING: handleOsc(cp); break;
            case OSC_ESC: handleOscEsc(cp); break;
            case DCS_PASSTHROUGH: handleDcs(cp); break;
            case DCS_ESC: handleDcsEsc(cp); break;
            case RECOVERY:
                if (cp==0x18||cp==0x1A) parserState=GROUND;
                else if (cp==0x1B) parserState=ESCAPE;
                break;
        }
    }

    static void handleGround(int cp) {
        if (cp==0x1B) parserState=ESCAPE;
        else if (cp==0x0D) cursorCol=0;
    }

    static void handleEscape(int cp) {
        if (cp=='[') { csiParamCount=0; csiCurrentParam=-1; csiPrivate=0; parserState=CSI_ENTRY; }
        else if (cp==']') { oscBuf.setLength(0); parserState=OSC_STRING; }
        else if (cp=='P') { dcsBuf.setLength(0); parserState=DCS_PASSTHROUGH; }
        else if (cp=='c') { resetState(); parserState=GROUND; }
        else if (cp=='X'||cp=='_') parserState=RECOVERY;
        else if (cp>=' '&&cp<='/') { }
        else if (cp>='@'&&cp<='~') parserState=GROUND;
        else if (cp==0x1B) { }
        else parserState=RECOVERY;
    }

    static void handleCsi(int cp) {
        // Private marker (only valid immediately after CSI)
        if (parserState==CSI_ENTRY) {
            if (cp=='?') { csiPrivate='?'; parserState=CSI_PARAM; return; }
            if (cp>='>'&&cp<='<') { csiPrivate=(char)cp; parserState=CSI_PARAM; return; }
            if (cp=='=') { csiPrivate='='; parserState=CSI_PARAM; return; }
        }
        // Parameter digit
        if (cp>='0'&&cp<='9') {
            parserState=CSI_PARAM;
            if (csiCurrentParam<0) {
                // Start new parameter
                if (csiParamCount<16) { csiCurrentParam=csiParamCount; csiParams[csiCurrentParam]=cp-'0'; }
            } else {
                // Continue accumulating current parameter
                csiParams[csiCurrentParam]=Math.min(csiParams[csiCurrentParam]*10+(cp-'0'), 9999);
            }
            return;
        }
        // Parameter separator: finalize current, prepare for next
        if (cp==';') {
            parserState=CSI_PARAM;
            if (csiCurrentParam>=0) { csiParamCount=csiCurrentParam+1; }
            else { csiParams[0]=0; csiParamCount=1; } // empty param = 0
            csiCurrentParam=-1;
            return;
        }
        // Intermediate bytes (0x20-0x2F)
        if (cp>=0x20&&cp<=0x2F) { parserState=CSI_IGNORE; return; }
        // Final byte (0x40-0x7E): execute
        if (cp>=0x40&&cp<=0x7E) {
            if (csiCurrentParam>=0) csiParamCount=csiCurrentParam+1;
            executeCsi((char)cp);
            parserState=GROUND;
            csiCurrentParam=-1;
            return;
        }
        // CAN / SUB: abort
        if (cp==0x18||cp==0x1A) { parserState=GROUND; csiCurrentParam=-1; return; }
        // ESC: abort, start new escape
        if (cp==0x1B) { parserState=ESCAPE; csiCurrentParam=-1; return; }
        // Invalid byte
        parserState=CSI_IGNORE;
    }

    static int getArg(int i, int def) { return i<csiParamCount?csiParams[i]:def; }

    static void executeCsi(char f) {
        if (csiPrivate=='?'&&f=='h') { decSet(true); return; }
        if (csiPrivate=='?'&&f=='l') { decSet(false); return; }
        switch(f) {
            case 'A': cursorRow=Math.max(0,cursorRow-getArg(0,1)); break;
            case 'B': cursorRow=Math.min(rows-1,cursorRow+getArg(0,1)); break;
            case 'C': cursorCol=Math.min(cols-1,cursorCol+getArg(0,1)); break;
            case 'D': cursorCol=Math.max(0,cursorCol-getArg(0,1)); break;
            case 'H': case 'f':
                cursorRow=Math.max(0,Math.min(rows-1,getArg(0,1)-1));
                cursorCol=Math.max(0,Math.min(cols-1,getArg(1,1)-1)); break;
            case 'm': applySgr(); break;
        }
    }

    static void decSet(boolean setting) {
        for (int i=0;i<csiParamCount;i++) {
            int m=csiParams[i];
            if (m==1) appCursorKeys=setting;
            else if (m==7) autoWrap=setting;
            else if (m==25) cursorVisible=setting;
            else if (m==1049) altScreen=setting;
        }
    }

    static void applySgr() {
        if (csiParamCount==0) { csiParams[0]=0; csiParamCount=1; }
        int i=0;
        while (i<csiParamCount) {
            int p=csiParams[i];
            if (p==0) { fgColor=0;bgColor=0;bold=false;underline=false;inverse=false; }
            else if (p==1) bold=true;
            else if (p==4) underline=true;
            else if (p==7) inverse=true;
            else if (p>=30&&p<=37) fgColor=p-30;
            else if (p>=40&&p<=47) bgColor=p-40;
            else if (p==38&&i+1<csiParamCount) {
                if (csiParams[i+1]==5) { fgColor=getArg(i+2,0); i+=2; }
                else if (csiParams[i+1]==2) { fgColor=0x1000000|(getArg(i+2,0)<<16)|(getArg(i+3,0)<<8)|getArg(i+4,0); i+=4; }
            }
            else if (p==48&&i+1<csiParamCount) {
                if (csiParams[i+1]==5) { bgColor=getArg(i+2,0); i+=2; }
                else if (csiParams[i+1]==2) { bgColor=0x1000000|(getArg(i+2,0)<<16)|(getArg(i+3,0)<<8)|getArg(i+4,0); i+=4; }
            }
            i++;
        }
    }

    static void handleOsc(int cp) {
        if (cp==0x07) { parserState=GROUND; }
        else if (cp==0x1B) parserState=OSC_ESC;
        else if (cp==0x18||cp==0x1A) parserState=GROUND;
        else if (oscBuf.length()<4096) oscBuf.append((char)cp);
    }

    static void handleOscEsc(int cp) {
        if (cp=='\\') parserState=GROUND;
        else { oscBuf.append('\u001B'); parserState=OSC_STRING; }
    }

    static void handleDcs(int cp) {
        if (cp==0x1B) parserState=DCS_ESC;
        else if (cp==0x18||cp==0x1A) parserState=GROUND;
        else if (dcsBuf.length()<4096) dcsBuf.append((char)cp);
    }

    static void handleDcsEsc(int cp) {
        if (cp=='\\') parserState=GROUND;
        else { dcsBuf.append('\u001B'); parserState=DCS_PASSTHROUGH; }
    }

    static int passed=0, failed=0;
    static void check(String name, boolean cond, String detail) {
        if (cond) { passed++; System.out.println("  PASS: "+name); }
        else { failed++; System.out.println("  FAIL: "+name+" "+detail); }
    }

    public static void main(String[] args) {
        System.out.println("=== Spike A: Native Parser Skeleton ===\n");

        resetState(); parserState=GROUND;
        feed(new byte[]{0x1B,'[','5','C'});
        check("T1: CSI 5C", cursorCol==5, "(col="+cursorCol+")");

        resetState(); parserState=GROUND;
        feed(new byte[]{0x1B,'[','3','1','m'});
        check("T2: SGR 31", fgColor==1, "(fg="+fgColor+")");
        feed(new byte[]{0x1B,'[','4','8',';','5',';','1','9','6','m'});
        check("T2: SGR 48;5;196", bgColor==196, "(bg="+bgColor+")");

        resetState(); parserState=GROUND;
        feed(new byte[]{0x1B,'[','?','1','0','4','9','h'});
        check("T3: ?1049h", altScreen, "");
        feed(new byte[]{0x1B,'[','?','1','0','4','9','l'});
        check("T3: ?1049l", !altScreen, "");

        resetState(); parserState=GROUND;
        feed(new byte[]{0x1B,'['});
        feed(new byte[]{'?','1','0','4'});
        feed(new byte[]{'9','h'});
        check("T4: split ?1049h", altScreen, "(alt="+altScreen+")");

        resetState(); parserState=GROUND;
        feed(new byte[]{0x1B});
        feed(new byte[]{'[','3','8'});
        feed(new byte[]{' ',';','5',';','1','0','0','m'});
        check("T5: split 38;5;100", fgColor==100, "(fg="+fgColor+")");

        resetState(); parserState=GROUND;
        feed(new byte[]{0x1B,']','2',';','H'});
        feed(new byte[]{'e','l','l','o',0x07});
        check("T6: split OSC", parserState==GROUND, "(state="+parserState+")");

        resetState(); parserState=GROUND;
        feed(new byte[]{0x1B,'[','?','9','9','9','9','h'});
        check("T7: unknown CSI safe", parserState==GROUND && cursorRow==0, "");

        resetState(); parserState=GROUND;
        feed(new byte[]{(byte)0xE4,(byte)0xB8});
        feed(new byte[]{(byte)0xAD});
        check("T8: split UTF-8", parserState==GROUND, "(state="+parserState+")");

        resetState(); parserState=GROUND;
        feed(new byte[]{0x1B,'[','?','1','h'});
        check("T9: DECCKM set", appCursorKeys, "");
        feed(new byte[]{0x1B,'[','?','1','l'});
        check("T9: DECCKM reset", !appCursorKeys, "");

        resetState(); parserState=GROUND;
        feed(new byte[]{0x1B,'[','?','7','l'});
        check("T10: DECAWM off", !autoWrap, "");

        resetState(); parserState=GROUND;
        byte[] seq = {0x1B,'[','?','1','0','4','9','h',0x1B,'[','3','1','m',0x1B,'[','5','H'};
        for (byte b : seq) feed(new byte[]{b});
        check("T11: byte-at-a-time alt", altScreen, "");
        check("T11: byte-at-a-time fg", fgColor==1, "(fg="+fgColor+")");
        check("T11: byte-at-a-time cursor", cursorRow==4&&cursorCol==0, "(row="+cursorRow+",col="+cursorCol+")");

        resetState(); parserState=GROUND;
        feed(new byte[]{0x1B,']','2',';','T','e','s','t',0x1B,'\\'});
        check("T12: OSC ST", parserState==GROUND, "(state="+parserState+")");

        resetState(); parserState=GROUND;
        feed(new byte[]{0x1B,'P','1','$','r',0x1B,'\\'});
        check("T13: DCS ignore", parserState==GROUND, "(state="+parserState+")");

        resetState(); parserState=GROUND;
        feed(new byte[]{0x1B,'[','!','X'});
        check("T14: malformed recovery", parserState==GROUND, "(state="+parserState+")");

        resetState(); parserState=GROUND;
        feed(new byte[]{(byte)0xC2,(byte)0x9B});
        check("T15: C1 UTF-8", parserState==GROUND, "(state="+parserState+")");

        System.out.println("\n=== Results: "+passed+" passed, "+failed+" failed ===");
        if (failed>0) System.exit(1);
    }
}
