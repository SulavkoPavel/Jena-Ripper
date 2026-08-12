# Jena Ripper

Jena Ripper — настольный исследователь RDF-графов на базе Apache Jena. Приложение позволяет подключаться к локальному TDB2 или к существующему CIM App API, искать RDF-ресурсы, визуально раскрывать связи, выполнять SPARQL-запросы и просматривать Owner Rules и Redis Rules.

![Jena Ripper](jena-ripper-frontend/src/assets/jena-ripper-approved.png)

## Скачать приложение

Portable-архивы уже содержат Java Runtime. Для запуска не нужно отдельно устанавливать Java, Maven, Node.js или базу данных.

| Операционная система | Архив | Запуск после распаковки |
|---|---|---|
| Windows 10/11 x64 | [Скачать Jena Ripper 1.0.0 для Windows x64](release/Jena-Ripper-1.0.0-Windows-x64.zip) | `Jena-Ripper/start.bat` |
| macOS Apple Silicon — M1/M2/M3/M4 | [Скачать Jena Ripper 1.0.0 для macOS arm64](release/Jena-Ripper-1.0.0-macOS-arm64.zip) | `Jena-Ripper/Jena Ripper.command` |
| macOS Intel | [Скачать Jena Ripper 1.0.0 для macOS x64](release/Jena-Ripper-1.0.0-macOS-x64.zip) | `Jena-Ripper/Jena Ripper.command` |

Контрольные суммы: [SHA256SUMS.txt](release/SHA256SUMS.txt).

> GitHub показывает содержимое ZIP вместо немедленного скачивания? Откройте ссылку на архив и нажмите **Download raw file**. Для публичных релизов эти же файлы удобно прикреплять к GitHub/GitLab Release без переименования.

## Установка и запуск

### Windows

1. Скачайте `Jena-Ripper-1.0.0-Windows-x64.zip`.
2. Полностью распакуйте архив в обычную папку. Не запускайте приложение прямо из окна ZIP.
3. Откройте папку `Jena-Ripper` и запустите `start.bat`.
4. Дождитесь открытия Jena Ripper в браузере.

Окно терминала является процессом приложения. Закрыть Jena Ripper можно кнопкой выключения в интерфейсе или закрытием этого окна.

### macOS

1. Выберите архив под процессор Mac: `arm64` для Apple Silicon либо `x64` для Intel.
2. Распакуйте ZIP.
3. Откройте папку `Jena-Ripper` и запустите `Jena Ripper.command`.

Архив не подписан сертификатом Apple Developer. Если macOS блокирует первый запуск, нажмите правой кнопкой по `Jena Ripper.command`, выберите **Открыть**, затем подтвердите запуск. Альтернативный способ для распакованной папки:

```bash
xattr -dr com.apple.quarantine /путь/к/Jena-Ripper
```

После этого снова запустите `Jena Ripper.command`.

## Как это работает

Portable-версия запускает локальный Spring Boot backend на `127.0.0.1` и свободном динамическом порту. Backend раздаёт готовый frontend и после запуска открывает адрес приложения в браузере. Интернет для самого интерфейса не нужен; сеть требуется только при работе с удалённым CIM App API.

```text
Браузер
   │ REST / JSON
   ▼
Jena Ripper — Spring Boot + Apache Jena
   ├── Local TDB2
   ├── CIM App API
   ├── PostgreSQL — Owner Rules
   └── Redis — Redis Rules и Redis Console
```

При переключении Connection Profile приложение автоматически закрывает прежний Dataset, мягко переинициализирует backend и обновляет страницу. Полностью закрывать и запускать Jena Ripper вручную при каждом переключении не требуется.

## Возможности

- визуальный Graph Explorer с раскрытием и сворачиванием ветвей;
- поиск по имени, URI, compact URI, UUID и RDF-классу;
- Node Inspector со свойствами, входящими и исходящими связями;
- read-only SPARQL Console: `SELECT`, `ASK`, `CONSTRUCT`, `DESCRIBE`;
- автоматическое добавление только необходимых SPARQL-prefixes;
- шаблоны, история, Query Analyzer, Jena Algebra и benchmark;
- Owner Rules с отдельным отображением AssetOwner и DataSource;
- Redis Rules и защищённая read-only Redis Console;
- профили подключений для Local TDB2 и CIM App API;
- отдельные настройки PostgreSQL и Redis;
- автоматическое применение выбранного профиля без ручного перезапуска;
- локальное хранение профилей, пользовательских шаблонов и истории.

SPARQL Update и изменяющие Redis-команды намеренно отключены.

## Первое подключение

Откройте настройки кнопкой с шестерёнкой в header и создайте либо выберите Connection Profile.

### Local TDB2

Укажите:

- режим `Local TDB2`;
- тип `TDB2`;
- путь к папке Dataset.

Один TDB2 Dataset нельзя одновременно открывать несколькими процессами. Если путь заблокирован, закройте другое приложение, которое использует эту же папку.

### CIM App API

Укажите:

- Base URL существующего CIM App;
- Auth URL, если он отличается от Base URL;
- Client ID и Client Secret;
- информационную модель;
- timeout-параметры.

Jena Ripper использует существующий API CIM App и не вносит изменения в его исходный код. Пароли не передаются во frontend после сохранения и не показываются в tooltip/header.

### PostgreSQL и Redis

PostgreSQL используется для Owner Rules при локальном режиме. Redis настраивается как отдельное прямое подключение для Redis Rules и Redis Console. Эти подключения не обязательны для базового просмотра RDF-графа.

## Где хранятся настройки

Профили подключений, секреты, пользовательские SPARQL/Redis-шаблоны и история хранятся вне папки приложения, поэтому не пропадают при обновлении или повторной распаковке архива.

| ОС | Настройки и профили | Логи |
|---|---|---|
| Windows | `%LOCALAPPDATA%\JenaRipper\jena-ripper-settings.json` | `%LOCALAPPDATA%\JenaRipper\logs\jena-ripper.log` |
| macOS | `~/Library/Application Support/JenaRipper/jena-ripper-settings.json` | `~/Library/Logs/JenaRipper/jena-ripper.log` |

Не публикуйте файл настроек: он может содержать пароли подключений.

## Запуск из исходного кода

Требования для разработки:

- JDK 17 или новее;
- Maven 3.9+;
- Node.js 20+ и npm.

Backend:

```powershell
cd jena-ripper-backend
mvn spring-boot:run
```

Frontend в отдельном терминале:

```powershell
cd jena-ripper-frontend
npm install
npm run dev
```

В режиме разработки frontend доступен по адресу `http://localhost:5173`, backend — `http://localhost:8082`. В portable-сборке фиксированный порт не используется: приложение само выбирает свободный порт и открывает правильный URL.

## Сборка и проверки

Backend tests:

```powershell
cd jena-ripper-backend
mvn test
```

Frontend production build:

```powershell
cd jena-ripper-frontend
npm run build
```

Сборка всех portable-архивов из подготовленных runtime-cache:

```powershell
.\BUILD-PORTABLE.bat
```

Или напрямую:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-all-portable.ps1
```

Подробности остальных вариантов упаковки находятся в [BUILD.md](BUILD.md).

## Структура проекта

```text
jena-ripper/
├── jena-ripper-backend/   Spring Boot, Apache Jena, REST API
├── jena-ripper-frontend/  Vite, JavaScript, D3, CodeMirror
├── packaging/             runtime-cache и шаблоны launchers
├── scripts/               release-скрипты
├── release/               готовые portable-архивы
├── BUILD.md               документация по сборке
└── README.md
```

## Безопасность

- приложение слушает только loopback-интерфейс `127.0.0.1`;
- выключение и автоматический restart доступны только локальному UI;
- SPARQL Console работает в read-only режиме;
- Redis Console разрешает только безопасный набор read-only команд;
- credentials не выводятся в header, tooltip и API-ответах настроек.
