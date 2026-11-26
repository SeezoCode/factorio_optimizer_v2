# Running in a cloud container (Linux, headless)

How the optimizer and the in-game check were set up in a Claude Code cloud session on 2026-11-26. Nothing here
survives the container; redo it in a new one (or turn it into the environment's setup script).

## 1. Java 25 (the build's toolchain)

The container had Java 21, and Gradle does not download toolchains here.

```bash
mkdir -p /opt/jdk && cd /opt/jdk
curl -sSL -o jdk.tgz "https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse"
tar xzf jdk.tgz && rm jdk.tgz
export JAVA_HOME=/opt/jdk/jdk-25*      # use the full folder name
./gradlew -q installDist -Porg.gradle.java.installations.paths=$JAVA_HOME
```

Maven Central sometimes answers 429 (too many requests) on the first build; run it again after a few seconds.

## 2. Factorio headless 2.1.24

The game version that `data/pyanodon.json` was exported with. **2.1.25 does not load Pyanodon**: it rejects the
`vessel` pipe ("Layers variation count mismatch ... 30 != 1").

```bash
cd /opt && curl -sSL -o h.tar.xz https://factorio.com/get-download/2.1.24/headless/linux64
tar xJf h.tar.xz && rm h.tar.xz                   # -> /opt/factorio
ln -sfn /opt/factorio/data /usr/share/factorio   # the scripts' config.ini reads __PATH__system-read-data__
export FACTORIO=/opt/factorio/bin/x64/factorio FACTORIO_USER_DIR=/opt/factorio-user
```

The headless build includes space-age, quality, elevated-rails and recycler, and needs no login.

## 3. Mods in `/opt/factorio-user/mods`

The scripts take mods from `$FACTORIO_USER_DIR/mods` (zips or folders).

- **Pyanodon code mods** from their public GitHub repositories, at the versions in `data/pyanodon.json`
  (`git clone --depth 1 --branch <version> https://github.com/pyanodon/<mod>`): pypostprocessing, pycoalprocessing,
  pyindustry, pyfusionenergy, pyrawores, pyhightech, pypetroleumhandling, pyalienlife, pyalternativeenergy, pymodpack.
- **enable-all-feature-flags** from `https://git.gay/reowned-factorio-mods/enable-all-feature-flags`.
- **The graphics mods and cargo-bays-and-unloaders** are only on the mod portal, which needs a Factorio login. They
  came from the desktop's mods folder, without images and sounds (the headless server loads none; even its own base
  has no `.png`), which made one 34 MB zip, pushed on the branch `mods-upload`:

  ```bash
  mkdir -p ~/Desktop/pymods-lite && cd ~/Desktop/pymods-lite
  for f in ~/Library/Application\ Support/factorio/mods/{py*graphics*,cargo-bays-and-unloaders}_*.zip; do
    unzip -qo "$f" -x '*.png' '*.jpg' '*.ogg' '*.wav'
  done
  cd .. && zip -qr pymods-lite.zip pymods-lite
  ```

  Empty stand-ins do not work: the graphics mods hold Lua that the code mods load (`util.sprite_load`).

## 4. What was checked

- `./gradlew installDist` builds.
- `jobs/py_science_1.json` with a 150 s budget solved to a valid 59 x 67 factory, and
  `verify.py <out> --mods pymodpack --no-space-age --job <job> --warmup 300 --measure 300` built all 684 entities
  with the 23 Pyanodon mods and **passed** (py-science-pack-1 at 100% of plan).
- A base-game block (`verify.py` with Space Age on) builds and runs.

## Does not work headless

- **`tools/factorio-exporter/export.py`**: the exporter mod writes only once a player is connected (it translates
  names and probes fluid boxes through the player), and the headless binary has no `--load-game`. Export data on the
  desktop and commit it; the cloud runs only need `data/*.json`.
- Krastorio2 is not installed (Krastorio2 and Krastorio2Assets are on codeberg.org; not tried).
