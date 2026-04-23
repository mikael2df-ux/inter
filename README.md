# inter — мост Mi Band 9 ↔ Android

Проект из двух частей, общающихся друг с другом через Xiaomi Wearable (interconnect):

- **Часовое приложение (quickapp, rpk)** — корень репозитория (`src/`, `build/`, `dist/`).
  На часах отображает заряд/сеть/IP телефона, по кнопке **Refresh** шлёт в телефон
  запрос `{"type":"get_status"}` и отрисовывает ответ.
- **Android-приложение (APK)** — `demo/XMS Wearable Demo/xms-wearable-sdk/`.
  На телефоне держит фоновый `InterconnectService`, который слушает сообщения от часов
  и отвечает JSON'ом со статусом телефона (`battery`, `network`, `ip`).

Оба приложения должны иметь **одинаковый `package`**: `com.xiaomi.xms.wearable.demo` —
именно по нему Mi Fitness роутит сообщения между часами и APK.

Краткий разбор протокола см. в разделе [Как работает связь](#как-работает-связь).

---

## Требования

- Windows 10/11 (инструкция ниже — для Windows, в WSL всё то же самое через `openssl`).
- Node.js ≥ 18 (проверено на 22).
- [AIoT IDE](https://iot.mi.com/vela/devtool) или просто `aiot-toolkit` (уже в `devDependencies`).
- Для Android-моста — Android Studio / JDK 17+ и реальный телефон
  с установленным **Mi Fitness** / **Xiaomi Wear**, к которому спарен Mi Band 9.

Пути в этом README — такие, как у меня в системе. У тебя они могут быть другими,
подставляй свои.

---

## Структура репозитория

```
inter/
├── src/                       # исходники часового приложения (.ux/.js/.json)
├── build/                     # результат `npm run build` (содержимое будущего .rpk)
├── dist/                      # готовый подписанный .rpk после `npm run release`
├── sign/                      # ключи подписи (ниже — как их сделать)
├── scripts/deploy.js          # кастомный деплой (`npm run deploy`)
├── demo/
│   ├── XMS Wearable Demo/     # Android-проект (наш мост: MainActivity + InterconnectService)
│   └── interconnect-demo/     # исходный пример от Xiaomi (не трогаем)
├── demoforanaly/              # оригинальные демо Xiaomi для референса
└── package.json
```

---

## 1. Часовое приложение (rpk)

### 1.1. Установка зависимостей

```powershell
npm install
```

### 1.2. Разработка с hot-reload

```powershell
npm run start
```

`aiot start --watch` поднимет dev-сервер и будет пересобирать проект при правках
`src/`. Подключаться к нему удобнее всего из AIoT IDE (кнопка Run) — она сама
протолкнёт hap в запущенный эмулятор.

### 1.3. Debug-сборка

```powershell
npm run build -- --devtool=inline-source-map
```

Складывает собранный бандл в `build/`. Параметр `--devtool=inline-source-map`
нужен, чтобы в логах эмулятора видеть нормальные имена файлов/строк из `.ux`,
а не колонки минифицированного `index.js`.

Пример лога (инфо):

```
🔷 [04-23 14:10:24] [toolkit]: start build: {
  projectPath: "C:\\Users\\Woo\\Documents\\miband9\\inter",
  options: {
    sourceRoot: "./src", signRoot: "./sign",
    releasePath: "./dist", outputPath: "./build",
    devtool: "inline-source-map", mode: "development",
    ...
  },
  node: "v22.22.2", platform: "win32", toolkit: "2.0.5"
}
```

### 1.4. Release-сборка (подписанный `.rpk`)

```powershell
npm run release
```

Сложит `dist\com.xiaomi.xms.wearable.demo.debug.1.0.0.rpk`. Для этого нужны
ключи в `sign/` — см. раздел [Сертификат для подписи](#сертификат-для-подписи).

### 1.5. Ускорение на физическом устройстве (`--enable-jsc`)

Скомпилированный `jsc` работает быстрее и заодно скрывает исходный JS.
Правишь `package.json`:

```json
{
  "scripts": {
    "start":   "aiot start --watch",
    "build":   "aiot build --enable-jsc",
    "release": "aiot release --enable-jsc",
    "lint":    "eslint --format codeframe --fix --ext .ux,.js src/",
    "deploy":  "node scripts/deploy.js"
  }
}
```

---

## 2. Эмулятор Vela

Эмулятор ставится вместе с AIoT IDE / Vela SDK. У меня он здесь:

```
C:\Users\Woo\.vela\sdk\emulator\windows-x86_64\emulator.exe
```

AVD создаётся из IDE (Tools → Virtual Device), имя записывается в
`C:\Users\Woo\.vela\vvd\`. В IDE мой AVD называется `xiaomi_band`, в референсном
руководстве — `Vela_Virtual_Band_9` (у тебя будет своё).

### 2.1. Запуск из IDE

IDE запускает эмулятор примерно так:

```
C:\Users\Woo\.vela\sdk\emulator\windows-x86_64\emulator ^
  -vela -avd xiaomi_band ^
  -show-kernel ^
  -network-user-mode-options hostfwd=tcp:127.0.0.1:10055-10.0.2.15:101 ^
  -qt-hide-window ^
  -qemu -device virtio-snd,bus=virtio-mmio-bus.2 -allow-host-audio -semihosting -smp
```

Пример из руководства (`Vela_Virtual_Band_9`):

```
C:\Users\firemoon\.export_dev\emulator\windows-x86_64\emulator ^
  -vela -avd Vela_Virtual_Band_9 ^
  -show-kernel ^
  -network-user-mode-options hostfwd=tcp:127.0.0.1:10055-10.0.2.15:101 ^
  -qt-hide-window ^
  -qemu -device virtio-snd,bus=virtio-mmio-bus.2 -allow-host-audio -semihosting
```

### 2.2. Запуск без IDE

Если хочется видеть окно эмулятора — **убери `-qt-hide-window`** (и по желанию
`-qt-no-window`, `-no-window`, если встретятся). Минимальный рабочий вариант:

```powershell
& "C:\Users\Woo\.vela\sdk\emulator\windows-x86_64\emulator.exe" `
  -vela -avd xiaomi_band `
  -show-kernel `
  -network-user-mode-options hostfwd=tcp:127.0.0.1:10055-10.0.2.15:101 `
  -qemu -device virtio-snd,bus=virtio-mmio-bus.2 -allow-host-audio -semihosting
```

- `-avd <имя>` — имя AVD из IDE.
- `-show-kernel` — печать логов ядра в консоль.
- `hostfwd=tcp:127.0.0.1:10055-10.0.2.15:101` — проброс порта (DevTools/inspector).

Файлы и конфиги эмулятора лежат в `C:\Users\Woo\.vela\vvd\`.

---

## 3. Установка rpk

### 3.1. В эмулятор (вручную, через adb)

IDE сама пушит `.rpk`, но иногда хочется поставить чужое/своё без IDE.

Нужен `adb` от Vela SDK (или Android). У меня есть оба:

- Vela SDK: `C:\Users\Woo\.vela\sdk\tools\adb\win\adb.exe`
- Локальный в `node_modules`: `inter\node_modules\@miwt\adb\bin\win\adb.exe`
- Android SDK: `C:\Users\Woo\AppData\Local\Android\Sdk\platform-tools\adb.exe`

Из руководства:

- AIoT IDE 1.5.x: `C:\Users\<user>\.aiot-ide\extensions\vela.aiot-core-1.5.3\dist\bin\win\adb.exe`
- AIoT IDE 1.6.0: `C:\Users\<user>\.aiot-ide\extensions\vela.aiot-emulator-1.6.0\dist\bin\win`

Последовательность установки `.rpk` в эмулятор:

```powershell
$adb = "C:\Users\Woo\.vela\sdk\tools\adb\win\adb.exe"
$rpk = "C:\Users\Woo\Documents\miband9\inter\dist\com.xiaomi.xms.wearable.demo.debug.1.0.0.rpk"
$pkg = "com.xiaomi.xms.wearable.demo"

# 1) закидываем .rpk
& $adb -e push $rpk /data/quickapp/app/

# 2) создаём каталог под приложение
& $adb -e shell "mkdir /data/quickapp/app/$pkg"

# 3) распаковываем
& $adb -e shell "unzip -o /data/quickapp/app/com.xiaomi.xms.wearable.demo.debug.1.0.0.rpk -d /data/quickapp/app/$pkg"

# 4) перезагружаем эмулятор — по-другому из запущенного приложения не выйти
& $adb -e shell "reboot"

# 5) запускаем приложение
& $adb -e shell "vapp $pkg &"
```

Такая схема позволяет перезапускать приложение без полной перезагрузки эмулятора
(повторно — с шага 3).

### 3.2. На физический Mi Band 9

1. Собери релиз: `npm run release` → получишь подписанный `.rpk` в `dist/`.
2. В Mi Fitness зайди в раздел разработчика (нужен включённый режим разработчика
   в Mi Fitness) и установи `.rpk` на браслет.
3. После установки приложение появится в списке приложений на браслете.

---

## 4. Отладка

- `console.log(...)` в `.ux`/`.js` часового приложения пишется в общий лог эмулятора.
- В AIoT IDE можно подцепить полноценный дебаггер (breakpoints, step).
- В Android Studio логи моста ищи по тегам `InterconnectSvc` и `MainActivity`.

### 4.1. Командная строка эмулятора

```powershell
$adb = "C:\Users\Woo\.vela\sdk\tools\adb\win\adb.exe"

# Свойства ОС
& $adb -e shell "getprop"

# Локаль (например, русская — для проверки локализации)
& $adb -e shell "setprop persist.locale ru_RU"

# Запустить системную оболочку часов (466x466, может подняться и на 192x490)
& $adb -e shell "miwear &"
```

---

## 5. Сертификат для подписи

`npm run release` требует ключей в `sign/` (для debug-сборки подойдёт debug-ключ;
в продакшн — свой уникальный). В Windows проще всего сгенерировать `openssl` через
WSL или любую Linux-машину:

```bash
mkdir sign && cd sign
openssl req -newkey rsa:2048 -nodes \
  -keyout private.pem -x509 -days 3650 -out certificate.pem
```

Положи `private.pem` + `certificate.pem` в `sign/` (у нас они уже в
`.gitignore`, так что под git не попадут).

---

## 6. Android-мост (demo/)

Это обычное Android-приложение, которое использует **xms-wearable-lib 1.4** (AAR
лежит в `demo/XMS Wearable Demo/xms-wearable-sdk/app/libs/`). Оно:

1. При старте просит у пользователя `POST_NOTIFICATIONS` и вызывает
   `AuthApi.requestPermission(did, DEVICE_MANAGER, NOTIFY)` через Mi Fitness.
2. Поднимает `InterconnectService` (ForegroundService), который:
   - слушает готовность xms-сервиса (`ServiceApi.registerServiceConnectionListener`);
   - берёт список подключённых часов (`NodeApi.connectedNodes`);
   - вешает `MessageApi.addListener(did, ...)` на каждый node;
   - подписывается на `DataItem.ITEM_CONNECTION`, чтобы перевешивать listener
     после реконнекта часов;
   - на входящее `{"type":"get_status"}` собирает через `PhoneStatusHelper`
     `{battery, network, ip}` и отвечает `MessageApi.sendMessage(did, bytes)`.

### 6.1. Сборка APK

```powershell
cd "C:\Users\Woo\Documents\miband9\inter\demo\XMS Wearable Demo\xms-wearable-sdk"
./gradlew.bat :app:assembleDebug
```

APK появится в `app\build\outputs\apk\debug\app-debug.apk`.

### 6.2. Установка и запуск

```powershell
$adb = "C:\Users\Woo\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb install -r "demo\XMS Wearable Demo\xms-wearable-sdk\app\build\outputs\apk\debug\app-debug.apk"
& $adb shell am start -n com.xiaomi.xms.wearable.demo/.MainActivity
```

Открой приложение **один раз**, выдай разрешение на уведомления и подтверди
диалог Mi Fitness о доступе к часам. Дальше мост живёт в фоне сам.

---

## Как работает связь

Один транспорт — Xiaomi Wearable Channel — с двух сторон:

| Сторона          | API                                      |
|------------------|------------------------------------------|
| Часы (quickapp)  | `@system.interconnect` → `conn.send/onmessage` |
| Телефон (APK)    | `Wearable.getMessageApi(ctx)` → `sendMessage / addListener` |

Маршрутизация по полю `package` в `manifest.json` часов и `applicationId` APK —
они должны совпадать (`com.xiaomi.xms.wearable.demo`).

**Часы → Телефон**

```js
// src/pages/index/index.ux
conn.send({ data: { type: 'get_status', t: Date.now() }, success, fail })
```

```kotlin
// InterconnectService.kt
messageApi.addListener(did) { nodeId, bytes ->
    val text = String(bytes, Charsets.UTF_8) // {"type":"get_status","t":...}
    ...
}
```

**Телефон → Часы**

```kotlin
messageApi.sendMessage(did, """{"type":"status","battery":73,"ip":"...","network":"Wi-Fi"}""".toByteArray())
```

```js
conn.onmessage = (d) => {
  const obj = typeof d.data === 'string' ? JSON.parse(d.data) : d.data
  // obj.battery / obj.ip / obj.network
}
```

На часах `conn.send(..)` может вернуть `fail.code = 202` — это «канал протух»,
надо пересоздать `interconnect.instance()` и ретрайнуть. Это уже реализовано в
`src/pages/index/index.ux`.

---

## 7. Известные приколы VelaOS

- `file.list` не умеет перечислять ресурсы приложения: на pre-release кидает
  исключение вместо нормальной ошибки.
- `file.readArrayBufferObject` может отказаться читать файл с
  `Invalid File Type` — он зависит от расширения. `.txt` читает.
- `.json`-файлы в `src/common/` могут не копироваться при сборке, это лечится
  конфигом сборки.
- `<image>` не отображает картинки из хранилища (`internal://files/1.png`).
  Иногда выручает `<image-animator>`.
- `<image>` поддерживает только PNG и JPEG. WebP — не проверено, BMP/TGA — нет.
- **Регистр имён папок имеет значение на реальных часах.** Если папка
  `common/`, а в коде ссылка `/Common/...` — эмулятор простит, Mi Band 9 — нет,
  причём без внятной ошибки.

---

## 8. Lint

```powershell
npm run lint       # ручной прогон
```

Хуков и `lint-staged` нет — линт гоняется вручную.

---

## Полезные ссылки

- Официальная документация quickapp Mi Vela: <https://iot.mi.com/vela/quickapp>
- PDF-документация по Xiaomi Wearable SDK лежит в `demo/` (файлы
  `小米穿戴第三方APP能力开放接口文档_1.4.pdf` и `interconnect测.pdf`) и
  дублируется в `demoforanaly/` для оригинального референса.
