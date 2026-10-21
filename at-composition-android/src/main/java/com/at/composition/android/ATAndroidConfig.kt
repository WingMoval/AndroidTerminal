// AT Composition Android — Configuration (V4-4).
//
// This allows consumers to specify a logical identity profile/name.
// The actual identity directory is determined internally from
// context.applicationContext.filesDir + identityProfile.
//
// Consumer CANNOT pass:
//   - raw File
//   - identityDir
//   - absolute path
//
// Identity must be PER_CONSUMER_APP_PRIVATE, SELF_GENERATED, NO_KEY_COPY.

package com.at.composition.android

/**
 * Configuration for ATAndroid.create().
 *
 * @property identityProfile a logical name for the identity profile.
 *        This is used to create an app-private subdirectory under
 *        context.applicationContext.filesDir.
 *
 *        Default: "adb_identity" (for the Product App)
 *        ConsumerHost: "at-cli-identity"
 *
 *        The actual path is: filesDir/<identityProfile>
 */
data class ATAndroidConfig(
    val identityProfile: String = "adb_identity",
)
