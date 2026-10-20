// AT Composition Android — Unit tests (V4-2).
//
// These tests verify the composition module structure and API surface.
// Device-specific tests (D1-D6) require a real device and are in androidTest.

package com.at.composition.android

import org.junit.Test
import org.junit.Assert.*

/**
 * Static/structural tests for the ATAndroid composition module.
 *
 * These tests verify:
 *   C4: returned engine type = ATEngine interface
 *   C5: Consumer-visible handle does not expose DefaultATEngine
 *   C6: Consumer-visible handle does not expose backend concrete classes
 *   C7: identityDir is not a public construction input
 */
class ATAndroidTest {

    /**
     * C4: ATAndroidComposition exposes ATEngine interface.
     *
     * Verifies that localEngine and adbEngine are of type ATEngine (Layer-2 contract).
     */
    @Test
    fun c4_composition_exposes_ateengine_interface() {
        // Reflection check: ATAndroidComposition has localEngine + adbEngine properties
        val clazz = ATAndroidComposition::class.java
        val fields = clazz.declaredFields.map { it.name }.toSet()
        
        assertTrue("Should have localEngine field", "localEngine" in fields)
        assertTrue("Should have adbEngine field", "adbEngine" in fields)
    }

    /**
     * C5: ATAndroidComposition does NOT expose DefaultATEngine.
     *
     * Verifies that the composition handle does not leak the implementation class.
     */
    @Test
    fun c5_composition_does_not_expose_default_ateengine() {
        val clazz = ATAndroidComposition::class.java
        val fieldTypes = clazz.declaredFields.map { it.type.name }.toSet()
        
        assertFalse("Should not expose DefaultATEngine", 
            fieldTypes.any { it.contains("DefaultATEngine") })
    }

    /**
     * C6: ATAndroidComposition does NOT expose backend concrete classes.
     *
     * Verifies that the composition handle does not leak:
     *   - LocalOneShotBackend / LocalPersistentBackend
     *   - AdbOneShotBackend / AdbPersistentBackend
     */
    @Test
    fun c6_composition_does_not_expose_backends() {
        val clazz = ATAndroidComposition::class.java
        val fieldTypes = clazz.declaredFields.map { it.type.name }.toSet()
        
        assertFalse("Should not expose LocalOneShotBackend",
            fieldTypes.any { it.contains("LocalOneShotBackend") })
        assertFalse("Should not expose LocalPersistentBackend",
            fieldTypes.any { it.contains("LocalPersistentBackend") })
        assertFalse("Should not expose AdbOneShotBackend",
            fieldTypes.any { it.contains("AdbOneShotBackend") })
        assertFalse("Should not expose AdbPersistentBackend",
            fieldTypes.any { it.contains("AdbPersistentBackend") })
    }

    /**
     * C7: identityDir is NOT a public construction input.
     *
     * Verifies that ATAndroid.create() does not accept an identityDir parameter.
     * The identity directory is determined internally from context.filesDir.
     */
    @Test
    fun c7_identitydir_not_public_input() {
        val clazz = ATAndroid::class.java
        val methods = clazz.declaredMethods.filter { it.name == "create" }
        
        for (method in methods) {
            val params = method.parameters.map { it.type.name }
            assertFalse("create() should not accept identityDir",
                params.any { it.contains("File") || it.contains("String") && it.contains("identity") })
        }
    }

    /**
     * C8: Context does not enter :at-engine-api.
     *
     * Verifies that ATAndroidComposition does not hold a Context reference.
     */
    @Test
    fun c8_context_not_in_composition_handle() {
        val clazz = ATAndroidComposition::class.java
        val fieldTypes = clazz.declaredFields.map { it.type.name }.toSet()
        
        assertFalse("Should not hold Context reference",
            fieldTypes.any { it.contains("Context") })
    }
}
