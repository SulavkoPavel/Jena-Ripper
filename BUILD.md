# Jena Ripper build

## Development

Development remains two-component and does not embed the frontend into the backend.

```powershell
cd jena-ripper-frontend
npm run dev
```

```powershell
cd jena-ripper-backend
mvn spring-boot:run
```

The Vite development build continues to call the Spring Boot API at `http://localhost:8082` unless `VITE_API_URL` overrides it.

## Production JAR

The `release` Maven profile performs `npm ci`, `npm run build`, copies `frontend/dist` to `backend/target/generated-resources/static`, and packages those generated files into the Spring Boot executable JAR.

```powershell
cd jena-ripper-backend
mvn clean package -Prelease
java -jar target/jena-ripper.jar
```

The frontend is served by Spring Boot at `/` and uses same-origin `/api` requests. No generated frontend files are copied into `src/main/resources` or committed.

## Portable Windows ZIP

Run on Windows with JDK 17, Maven, and Node.js available to the build machine:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-windows-portable.ps1
```

The script uses the Maven `release` profile, creates a Windows x64 runtime with `jlink`, and produces:

```text
release/Jena-Ripper-<version>-Windows-x64.zip
```

After extracting the archive, start `Jena-Ripper\start.bat`. It always uses `runtime\bin\java.exe` from the archive. The analyst does not need Java, Node.js, Maven, an installer, or administrator access.

## Portable macOS ZIP

The macOS runtime must be created on a Mac of the matching architecture. Run on an Apple Silicon or Intel Mac with JDK 17, Maven, and Node.js available to the build machine:

```bash
chmod +x ./scripts/build-macos-portable.sh
./scripts/build-macos-portable.sh
```

Depending on the build Mac, the result is:

```text
release/Jena-Ripper-<version>-macOS-arm64.zip
release/Jena-Ripper-<version>-macOS-x64.zip
```

After extracting, start `Jena-Ripper/Jena Ripper.command`. The archive contains its own platform-specific `runtime/bin/java`. macOS may require confirmation through Finder's Open action on first launch; the script does not bypass Gatekeeper.

Portable Windows and macOS packaging uses `jlink` and ZIP only. It does not use Docker, Electron, Tauri, or `jpackage`.

Portable settings and logs are outside the extracted directory:

```text
Windows settings: %APPDATA%\jena-ripper\profiles.json
Windows logs:     %LOCALAPPDATA%\JenaRipper\logs\jena-ripper.log
macOS settings:   ~/.jena-ripper/profiles.json
macOS logs:       ~/Library/Logs/JenaRipper/jena-ripper.log
```

## Windows package

Use a JDK 17 containing `jpackage`. An `app-image` includes its own Java runtime and does not require WiX:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-windows-release.ps1 -PackageType app-image
```

For an `.exe` installer, install WiX Toolset 3.x and make `candle.exe` and `light.exe` available in `PATH`:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-windows-release.ps1 -PackageType exe
```

Generated artifacts are written to:

```text
release/
  jar/jena-ripper.jar
  windows/app-image/Jena Ripper/
  windows/Jena-Ripper-<version>.exe
```

The desktop launcher enables the `desktop` Spring profile. It binds only to `127.0.0.1`, selects a free port, logs the final URL, and opens that URL once after Spring Boot is ready.

Desktop connection profiles are stored in `%APPDATA%\jena-ripper\profiles.json` on Windows and `~/.jena-ripper/profiles.json` on macOS/Linux. A legacy `jena-ripper-settings.json` is copied once when the new file does not yet exist. Logs remain outside the project directory.
