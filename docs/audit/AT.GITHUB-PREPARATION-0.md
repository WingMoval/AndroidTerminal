# AT.GITHUB-PREPARATION-0 �?GitHub 正式发布前审计与准备

**Date:** 2026-10-22
**Nature:** GIT_AUDIT + PUBLICATION_PREPARATION (read-only; no modifications made)
**Scope:** AndroidTerminal-V4 repo + historical AT repos under D:\codebank\AndCode

---

## 最终报�?
```text
STEP = AT.GITHUB-PREPARATION-0
VERDICT = READY_WITH_GAPS

AT_ROOT = D:\codebank\AndCode\AndroidTerminal-V4
AT_START_HEAD = 7f3a7dface6e504970714631ba4fb2224052f5ef
AT_FINAL_HEAD = 7f3a7dface6e504970714631ba4fb2224052f5ef (unchanged; read-only audit)
AT_BRANCH = master
AT_WORKTREE = CLEAN (4 untracked files in 本轮交付/ �?not committed)

EXISTING_REMOTE = origin �?D:\codebank\AndCode\AndroidTerminal-V3 (local path)
REMOTE_PUSHED = NO

V1_HISTORY_FOUND = NO (AndroidTerminal/ has source files but NO .git)
V2_HISTORY_FOUND = YES (AndroidTerminal-V2: independent repo, 30 commits, branch main)
V3_HISTORY_FOUND = YES (in V4 repo: commits cf66e53..75d5832, 45 commits)
V4_HISTORY_FOUND = YES (in V4 repo: commits 0ef538b..7f3a7df, 20 commits)

HISTORY_CONTINUITY = MULTIPLE_INDEPENDENT_HISTORIES
HISTORY_INTEGRITY = V3+V4 VERIFIED (65 commits, no gaps); V2 VERIFIED (30 commits, independent); V1 LOST (no git)
HISTORY_MIGRATION_REQUIRED = YES (V2 independent history must be imported as branch)

FREEZE_TAG_EXISTS = YES
FREEZE_TAG_TARGET = 7216d0e8131e92e8914ce7c8443e1668b1d53aca
FREEZE_TARGET_MATCH = YES
FREEZE_TAG_MODIFIED = NO

CONSUMER_BUNDLE_PRESENT = YES
MANIFEST_VERIFIED = YES
ARTIFACT_COUNT_EXPECTED = 8
ARTIFACT_COUNT_VERIFIED = 8
SHA256_MISMATCH_COUNT = 0

SECRET_SCAN_STATUS = PASS
HISTORICAL_SECRET_RISK = LOW
LARGE_FILE_AUDIT = 15.53 MB total; largest = boringssl lib (4.2MB) + AAR (1.2MB)
LICENSE_STATUS = LICENSE_DECISION_REQUIRED (no LICENSE file)
README_STATUS = STALE (V3-era content)
GITIGNORE_STATUS = PASS

FILES_CHANGED = 0
COMMITS_CREATED = 0
TAGS_CREATED = 0
TAGS_MOVED = 0
HISTORY_REWRITTEN = NO
PUSH = NO
RELEASE_CREATED = NO

STOP = YES
```

---

## 阶段 A：Git 仓库 Preflight

### A1. 仓库状�?
| 字段 | �?|
|------|-----|
| toplevel | `D:/codebank/AndCode/AndroidTerminal-V4` |
| HEAD | `7f3a7dface6e504970714631ba4fb2224052f5ef` |
| branch | `master` |
| worktree status | `## master...origin/master [ahead 20]` + 4 untracked (本轮交付/) |
| remotes | `origin �?D:\codebank\AndCode\AndroidTerminal-V3` (local path) |
| shallow | `false` |
| total commits | 65 |
| branches | `master` (only local); `remotes/origin/master` (= `75d5832`) |

### A2. 标签

| Tag | Type | Target | 说明 |
|-----|------|--------|------|
| `AT.PRE-EMULATOR-FREEZE-V1` | annotated | `7ccb1bf` | V3 时代 |
| `AT.AG-CONSUMER-FREEZE-V1` | annotated | `a6f23d7` | V3 AG consumer freeze |
| `AT.V3-TO-V4-BASELINE-V1` | annotated | `064a855` | V3→V4 基线 (= `75d5832` commit) |
| `AT.V4-CONSUMER-FREEZE-V1` | annotated | `75aacc5` (tag obj) �?`7216d0e` (commit) | **V4 冻结标签** |

### A3. 冻结标签验证

```text
TAG = AT.V4-CONSUMER-FREEZE-V1
TAG_OBJECT = 75aacc58399ef9856614d02a4c31246ea94213d3 (annotated)
TAG_TARGET = 7216d0e8131e92e8914ce7c8443e1668b1d53aca
TAGGER = AT V3 Bootstrap <at-v3@local> 1792641838 +0800
TAG_MESSAGE = "AndroidTerminal V4 consumer boundary freeze"

ANCESTRY: 7216d0e is ancestor of 7f3a7df (HEAD) = TRUE
DIFF 7216d0e..7f3a7df = 8 files (all docs), 0 production code
  - docs/evidence/at_v4_6/14_final_freeze_verdict.md
  - docs/freeze/AT.V4-CONSUMER-FREEZE-V1.md
  - docs/handoff/AT_V4_CONSUMER_HANDOFF.md
  - docs/implementation/AT.V4-6_Device_Parity_and_Consumer_Freeze.md
  - 4 files in 本轮交付/ (copies of the above)
```

### A4. Ahead commits (vs origin/master = 75d5832)

20 commits: `0ef538b` (AT.V4-0) �?`7f3a7df` (AT.V4-6.1), all V4 work.

---

## 阶段 B：从 V1 开始的完整历史核验

### B1. 各版本仓库清�?
| 仓库路径 | .git | 最早提�?| 当前 HEAD | 分支 | 标签 | 提交�?| �?V4 关系 | 历史完整�?|
|---------|------|---------|----------|------|------|--------|----------|----------|
| `AndroidTerminal-V4` | YES (dir) | `cf66e53` 2026-10-10 "AT.V3-0" | `7f3a7df` | master | 4 | 65 | **主仓�?* (V3 45 + V4 20) | V3→V4 连续已验�?|
| `AndroidTerminal-V2` | YES (file=worktree) | `2baa189` "AT.V2-0" | `726a441` | main | 2 (at-v0.1.0, at-v2-baseline-0) | 30 | **独立历史** | 已验�?|
| `AndroidTerminal-V3` | YES (file=worktree) | `cf66e53` (same as V4) | `75d5832` | master | (shared with V4) | 45 | V4 �?V3 部分 (origin) | 已验�?|
| `AndroidTerminal-Public` | YES (dir) | �?(empty) | �?| master | 0 | 0 | 无关 | 空仓�?|
| `AndroidTerminal` | **NO** | �?| �?| �?| �?| 0 | **�?Git 历史** | **历史丢失** |

### B2. Git 关系�?
```text
V2 (INDEPENDENT repo, 30 commits, branch main)
  2baa189 �?... �?726a441 [main]
  tags: at-v0.1.0, at-v2-baseline-0
  NO shared ancestry with V4 repo
  has worktree at <USER_HOME>\AppData\Local\Temp\atrv_pre3b (detached 7a979a6)

V4 repo (master, 65 commits)
  V3 portion (45 commits):
    cf66e53 (2026-10-10) �?f0016d6 �?d11cd3c �?... �?75d5832 [origin/master]
  V4 portion (20 commits):
    0ef538b (AT.V4-0) �?... �?7216d0e [AT.V4-CONSUMER-FREEZE-V1] �?7f3a7df [HEAD]

V1 (AndroidTerminal/): source files only, NO .git, NO commit history
```

### B3. 历史连续性判�?
```text
HISTORY_CONTINUITY = MULTIPLE_INDEPENDENT_HISTORIES

- V1: NO git history (source files exist but were never committed to git)
- V2: INDEPENDENT git history (30 commits, no shared ancestry with V4)
- V3+V4: CONTINUOUS in single repo (65 commits, V3→V4 verified)
```

### B4. 历史上传方案

**推荐：主仓库 + 独立历史分支**

```text
GitHub repo: android-terminal (or preferred name)

master branch:
  = V4 repo master (65 commits: V3 45 + V4 20)
  = all 4 existing V3/V4 tags
  = freeze tag AT.V4-CONSUMER-FREEZE-V1 preserved at 7216d0e

v2-history branch:
  = V2 repo main (30 commits, independent history)
  = V2 tags (at-v0.1.0, at-v2-baseline-0)
  = NOT merged into master (preserves original SHA/author/date)
  = README explains independent origin

V1:
  = No git history exists
  = Documented in README as pre-git legacy
  = If source preservation needed: single commit branch "v1-snapshot"
    (clearly labeled, no fake history)
```

**为什么不合并 V2 �?master�?*
- 无共享祖先；合并会创�?merge commit 暗示 V2 �?V4 前身
- 伪造历史连续�?- 独立分支保留 V2 原始 SHA、作者、时�?
---

## 阶段 C：公开发布安全审计

### C1. 密钥和敏感数�?
| 检查项 | 结果 | 风险 |
|--------|------|------|
| adbkey / 私钥 / 身份文件 | 未发�?| �?|
| API Key / Token / 密码 | 未发�?| �?|
| 签名密钥 / 密钥�?(.p12/.keystore) | 未发�?| �?|
| 配对码明�?| 未发�?| �?|
| 个人主机�?| `developer@HOST` �?3 �?| LOW |
| 内部 IP | `<LAN_IP>` �?2 �?| LOW |
| 个人路径 (D:\codebank, C:\Users) | 未在 tracked 源码中发�?| �?|
| Git 历史敏感文件 | 无（仅上述主机名/IP�?| LOW |

**个人主机名出现位置：**

| 文件 | �?| 内容摘要 |
|------|-----|---------|
| `docs/evidence/at_v3_2_2/EVIDENCE.md` | 42 | "PC adbkey `developer@HOST`" |
| `docs/evidence/at_v3_2_3/EVIDENCE.md` | 42 | "PC adbkey `developer@HOST`" |
| `test-host/src/androidTest/.../AdbBackendHeadlessTest.kt` | 39 | "device already trusts developer@HOST" |

**内部 IP 出现位置�?*

| 文件 | �?| 内容摘要 |
|------|-----|---------|
| `at-cli/src/test/java/com/at/cli/AdbCliArgsTest.kt` | 188,191 | test fixture `<LAN_IP>:38619` |
| `docs/implementation/AT.CONSUMER-BOOTSTRAP-0.md` | 132 | evidence `adb pair <LAN_IP>:38619` |

### C2. 大文件和生成文件

```text
Total tracked size: 15.53 MB
```

| 文件 | 大小 | 建议 |
|------|------|------|
| `adb-engine/src/main/cpp/third_party/boringssl/lib/arm64-v8a/libboringssl.a` | 4.2 MB | 纳入仓库（构建依赖）�?D6 决策 |
| `dist/AT.V4-CONSUMER-FREEZE-V1/artifacts/*.aar` (6 files) | ~1.6 MB total | 纳入仓库（受控目录）�?Release 附件（D5�?|
| `dist/AT.V4-CONSUMER-FREEZE-V1/artifacts/*.jar` (2 files) | ~25 KB | 同上 |
| `docs/evidence/**/*.png` (多张) | ~3 MB total | 纳入仓库（审计证据） |
| `gradle/wrapper/gradle-wrapper.jar` | 42 KB | 纳入仓库（标准） |
| `adb-engine/src/main/cpp/third_party/boringssl/include/**/*.h` | ~1 MB | 纳入仓库（头文件�?|

所有文�?< GitHub 100MB 限制。无需 LFS�?
### C3. 许可证与 README

**LICENSE�?* 不存在。`LICENSE_DECISION_REQUIRED`�?
**README.md (2658B)�?* 标题 "AndroidTerminal-V3"，内容为 V3 时代。未提及 V4、冻结标签、消费者制品。需要更新�?
**.gitignore (298B)�?* PASS。覆盖：
- `local.properties`, `.gradle/`, `build/`, `.cxx/`
- `*.apk`, `*.aab`, `*.jar` (!gradle-wrapper), `*.so`
- `.idea/`, `*.iml`, `.vscode/`, `.DS_Store`
- `*.key`, `*.pem`, `*.p12`, `env/`, `secrets/`

---

## 阶段 D：V4 冻结标签与消费者制品核�?
### D1. 冻结标签

| 字段 | �?|
|------|-----|
| 标签�?| `AT.V4-CONSUMER-FREEZE-V1` |
| 类型 | annotated (tag object `75aacc5`) |
| 目标提交 | `7216d0e8131e92e8914ce7c8443e1668b1d53aca` |
| 预期匹配 | YES |
| 后续提交 | `7f3a7df` (docs-only, 8 files, 0 production code) |
| 标签被修�?| NO |

### D2. 消费者制�?
```text
Bundle path: dist/AT.V4-CONSUMER-FREEZE-V1/
Contents:
  README.md                    1994B
  SHA256SUMS.txt                715B
  artifacts/adb-engine-debug.aar     1261778B
  artifacts/at-backend-adb-debug.aar     8861B
  artifacts/at-backend-local-debug.aar 133778B
  artifacts/at-cli-debug.aar         196881B
  artifacts/at-composition-android-debug.aar  31197B
  artifacts/at-engine-api.jar        23079B
  artifacts/at-engine-debug.aar        2939B
  artifacts/at-engine-spi.jar         1851B
  docs/AT.V4-CONSUMER-FREEZE-V1.md   2565B
  docs/AT_V4_CONSUMER_HANDOFF.md     7358B
```

**SHA256 验证�?/8 PASS, 0 MISMATCH, 0 MISSING**

**Bundle 敏感文件扫描：无**

**POM 文件：无**（raw AAR/JAR，非 Maven publication；消费者需自行构建依赖描述�?
### D3. GitHub 制品分发策略

| 方案 | 优点 | 缺点 |
|------|------|------|
| A: Git 仓库受控目录（当前） | 版本追溯、hash 验证、与源码同仓�?| 仓库略大（~1.6MB AARs�?|
| B: GitHub Release 附件 | 仓库轻量、下载便�?| 需额外管理、版本追溯弱 |
| C: 两者结�?| 源码仓库有目�?+ Release 有附�?| 维护两份 |

推荐：方�?A（当前已实现），制品�?git 中受版本控制。Release 附件作为可选补充�?
---

## 阶段 E：发布准备结�?
### E1. 发布阻断�?
| 级别 | 项目 | 说明 |
|------|------|------|
| **BLOCKER** | LICENSE 缺失 | 无许可证文件，无法公开 |
| **BLOCKER** | README 过期 | 标题 "AndroidTerminal-V3"，V3 时代内容 |
| RECOMMENDED | 个人主机�?| `developer@HOST` �?3 �?evidence/test 文件 |
| RECOMMENDED | 测试 IP | `<LAN_IP>` �?2 �?test/evidence 文件 |
| RECOMMENDED | BoringSSL 第三方库 | 4.2MB static lib + 1MB headers |
| PASS | .gitignore | 覆盖完整 |
| PASS | SHA256 | 8/8 |
| PASS | 冻结标签 | 存在、类型正确、目标正�?|
| PASS | 消费者制�?| 8 个完整、无敏感文件 |
| PASS | 无密�?密码 | tracked + history 均无 |

### E2. Controller 决策�?
| # | 决策 | 选项 |
|---|------|------|
| D1 | 许可�?| Apache-2.0 / MIT / GPL-3.0 / 其他 / 不公开 |
| D2 | V2 历史导入 | 分支（推荐）/ 独立仓库 / 不导�?|
| D3 | README 更新 | 最小更新（�?V4 段落�? 全面重写 |
| D4 | 个人主机名脱�?| 修改 3 个文�?/ 接受 |
| D5 | 制品分发 | git 目录（推荐）/ Release 附件 / 两�?|
| D6 | BoringSSL 第三方库 | 保留 / 移除（需评估构建影响�?|

### E3. 推荐的首次推送命令草�?
```powershell
# Step 1: GitHub 创建空仓库（不初始化�?#   手动: github.com �?New repository �?不勾�?README/.gitignore/LICENSE

# Step 2: 添加 remote
Set-Location "D:\codebank\AndCode\AndroidTerminal-V4"
git remote add github https://github.com/<user>/android-terminal.git

# Step 3: 推�?master + 标签
git push github master
git push github --tags

# Step 4: 导入 V2 历史为独立分�?git fetch "D:\codebank\AndCode\AndroidTerminal-V2" main
git push github FETCH_HEAD:refs/heads/v2-history
git push github at-v0.1.0 at-v2-baseline-0

# Step 5: (可�? 清理本地 remote
# git remote remove origin
# git remote rename github origin
```

### E4. 上传后远端验�?
```powershell
# 1. master SHA
git ls-remote github refs/heads/master
# 期望: 7f3a7dface6e504970714631ba4fb2224052f5ef

# 2. 所有标�?git ls-remote github --tags
# 期望: 6 个标�?
# 3. 冻结标签目标
git ls-remote github "refs/tags/AT.V4-CONSUMER-FREEZE-V1^{}"
# 期望: 7216d0e8131e92e8914ce7c8443e1668b1d53aca

# 4. V2 分支
git ls-remote github refs/heads/v2-history
# 期望: 726a441acd5d5a7e24c5015adce57e543d1897f7

# 5. Clone 验证
git clone https://github.com/<user>/android-terminal.git /tmp/verify
git -C /tmp/verify rev-list --count master        # 65
git -C /tmp/verify rev-list --count v2-history    # 30

# 6. SHA256 验证
cd /tmp/verify/dist/AT.V4-CONSUMER-FREEZE-V1/artifacts
Get-FileHash *.aar *.jar -Algorithm SHA256
# �?SHA256SUMS.txt 逐一对比
```

---

## 操作记录

```text
FILES_CHANGED = 0
COMMITS_CREATED = 0
TAGS_CREATED = 0
TAGS_MOVED = 0
HISTORY_REWRITTEN = NO
PUSH = NO
RELEASE_CREATED = NO
```

本轮为纯只读审计，未修改任何文件�?
**STOP.**
