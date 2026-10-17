# Shared emulator input contract

- Follow the workspace-root AGENTS.md device rules. Multiple chats share this emulator.
- Computer mouse wheel scrolling was confirmed by the user with a visible standalone emulator and `-feature -VirtioMouse,-VirtioTablet`. Enabling `VirtioTablet` breaks scrolling; enabling relative `VirtioMouse` previously broke clicking.
- Do not launch emulator.exe directly with hand-written arguments. Use workspace-root `start_emulator_wheel.ps1 -ColdBoot` only for an authorized restart, or `run_latest.cmd` for normal build/update. Reuse an online emulator for ordinary APK updates.
- Root `emulator_input.ps1` is the only source for launch arguments. Never replace its flags to address mouse capture, rendering, microphone or APK-installation problems.
- Root `restore_emulator_app.ps1` verifies both app foreground and host wheel input mode. If this check fails, report the conflicting mode and fix through the shared launcher rather than claiming successful completion.
- Do not run instrumentation or install the test APK unless the user explicitly requests device tests. Never clear user data or uninstall the app to fix input.
