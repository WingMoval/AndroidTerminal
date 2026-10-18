# :app native — AT.CLI-CONSUMER-0.
#
# The shell-visible `adb` CLI frontend (app_process AtCliMain) and the same-UID
# UDS bridge server (atcli_env) now live in the REUSABLE :at-cli module
# (AT.CLI-CONSUMER-0). :app consumes them via the :at-cli dependency.
#
# The legacy native ELF `adb` (adb_cli.cpp) is NO LONGER BUILT: it was the
# original AT.CLI-0 entrypoint, but SELinux blocks untrusted_app from executing
# a private files/bin ELF (RC=126). The verified launcher is the app_process
# shell function (SELECTED_CLI_ENTRYPOINT = APP_PROCESS, AT.CLI-0.1). The ELF
# source is kept for reference only and is removed from the build.
#
# :app keeps a minimal native helper (atapp_env) for the CLI frontend
# provisioning setenv (CliFrontendProvisioner) and the debug UDS test client
# (CliProbe). The bridge server itself is in :at-cli (atcli_env).

cmake_minimum_required(VERSION 3.22.1)

project("atAppEnv" LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)

# :app keeps only the setenv / UDS-test helpers (the bridge server is in
# :at-cli). NativeEnv.kt (com.androidterminal.app.cli.NativeEnv) loads this.
add_library(atapp_env SHARED
    app_env.cpp
)
target_link_libraries(atapp_env log)
