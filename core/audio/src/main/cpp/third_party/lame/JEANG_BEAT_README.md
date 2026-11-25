# LAME 3.100 (MP3 encoder)

- Source: https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz
- SHA-256 of the tarball: `ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e`
- Licence: GNU LGPL version 2 or later (see `COPYING` and `LICENSE`).

Only `libmp3lame/*.c|*.h` and `include/lame.h` are included, unmodified, plus a minimal
`config.h` written for this project. The `vector/` SSE code and the `mpglib` decoder are
not used. LAME is built as its own shared library (`libmp3lame.so`) and Jeang Beat links to
it dynamically, as the LGPL requires; you can rebuild it from this source.
