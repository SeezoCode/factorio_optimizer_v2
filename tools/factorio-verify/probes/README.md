# Probes for live check runs

Helpers for watching and tracing `verify.py` runs while they play (RCON into the check's mod). Run from the repository
root. Output (samples, snapshots) goes to `out/`, which git ignores.

- `go.sh <new run> <run to copy the layout from> <verify flags...>`: copy a layout into `solves/<new run>` and start
  the check on it in the background (`solves/<new run>/verify.out`).
- `lua.py <run> <file.lua>`: run a Lua probe in a live run and print what it prints (`storage.run` is the check's
  state). Lines starting with `--` are dropped (the probe goes over RCON as one line).
- `samp.sh <probe.lua> <run...>`: the probe every two minutes into `out/samp2/<run>.jsonl` (`ls.lua`: loop items on
  belts and in machines). `sa.py <run...>` reads them: pack crafts and loop stocks by game hour.
- `samp_bash.sh`: the same in bash, for machines without zsh (the cloud container); `ls_logi.lua` is `ls.lua` with
  logistic's mukmoux loop items added.
- `sum.py <run> [hours]`: pack crafts by hour, how the recipes kept to plan over the last hours, what was handed in.
- `chainlive.py <run> <recipe>`: from the target recipe down, what each stopped machine lacks right now.
- `fq.lua`, `mq.lua`, `nq.lua`, `sq2.lua`, `kt.lua`, `dt2.lua`, `gall.lua`, `why.lua`: one-off probes (a machine's
  inserters and belts, a belt network by id, reserve gates, loop users short under the time reserve, machines that
  place results directly, gates shut most of the run, why no gate was built). Edit the names or ids at their top.
- `stop.sh <run...>`: stop runs and delete the temp game folders no live game uses (`clean.py`). Never run
  `clean.py` right after starting runs.
