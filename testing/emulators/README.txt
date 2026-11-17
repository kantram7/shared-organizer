Локальные Android-эмуляторы

Два независимых AVD на Android 16 / API 36, Google APIs x86_64:
Пользователь 1: SharedOrganizer_User1, ADB serial emulator-5554.
Пользователь 2: SharedOrganizer_User2, ADB serial emulator-5556.
У каждого свои данные приложения, 2 ГБ RAM и 2 виртуальных ядра.
Это стандартный Android, а не точная копия оболочки физического телефона.

Инструменты находятся в <EnvironmentRoot>/tools/android-sdk.
Виртуальные устройства — в <EnvironmentRoot>/runtime/avd.
Журналы — в <RepositoryRoot>/testing/local/logs/emulators.
Путь EnvironmentRoot определяется из игнорируемого build.local.json или задаётся параметром явно.

Команды из корня репозитория в PowerShell:
.\testing\emulators\setup-android-devices.ps1
.\testing\emulators\android-devices.ps1 -Action Check
.\testing\emulators\android-devices.ps1 -Action Start -Device User1
.\testing\emulators\android-devices.ps1 -Action Start -Device All
.\testing\emulators\android-devices.ps1 -Action Install -Device All
.\testing\emulators\android-devices.ps1 -Action Status
.\testing\emulators\android-devices.ps1 -Action Stop -Device All

Start открывает отдельные окна, которыми можно пользоваться мышью и клавиатурой.
setup-android-devices.ps1 создаёт два профиля после установки SDK-пакетов emulator и system-images;android-36;google_apis;x86_64. При повторном запуске существующие профили сохраняются.
Install ставит APK последней локальной сборки и открывает приложение. Для другого APK передайте -ApkPath.
Обновление через install -r сохраняет данные; не удаляйте приложение, если они нужны.
-ColdBoot пропускает загрузку снимка, но не очищает диск устройства.
-Headless запускает без окна для автоматических проверок.
Если auto выбирает программную графику из-за несовместимого Vulkan, можно проверить -GpuMode host -DisableVulkan: используется OpenGL видеокарты без Vulkan. Поддержка зависит от драйвера. Для возврата используйте -GpuMode auto без -DisableVulkan; смена режима требует перезапуска AVD. Если аппаратный режим показывает чёрный экран или артефакты, используйте -GpuMode swiftshader -DisableVulkan (программная графика, больше нагрузка на CPU).
На компьютере с 16 ГБ RAM перед одновременным запуском двух устройств рекомендуется освободить примерно 7 ГБ. Скрипт проверяет память до запуска; -AllowLowMemory снимает эту проверку.

Для независимой работы агентов:
1. Закрепить за каждым только один serial из таблицы выше.
2. Всегда указывать adb -s <serial>; не использовать команды ко всем устройствам.
3. Проверять имя AVD перед установкой, действиями и остановкой.
4. Скриншоты и журналы сохранять вне Git в отдельных каталогах User1 и User2.
5. Не использовать общий курсор Windows для параллельных действий. Управлять Android через ADB и адресованные команды/тесты.
6. Не изменять данные второго устройства и не останавливать общий сервер ADB.

Облачный режим использует независимые Firebase-аккаунты на каждом AVD. Для временных анонимных тестеров предусмотрена отдельная debug-сборка и явно назначенные сервером роли; инструкции — testing/firebase/README.txt. Локальное демо само по себе между устройствами не синхронизируется.

Официальная документация:
https://developer.android.com/studio/run/emulator-commandline
https://developer.android.com/studio/run/emulator-acceleration
https://developer.android.com/tools/adb

https://developer.android.com/studio/run/emulator-troubleshooting
