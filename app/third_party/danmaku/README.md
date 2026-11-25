# DanmakuFlameMaster 0.9.25

Source: [upstream v0.9.25](https://github.com/bilibili/DanmakuFlameMaster/tree/v0.9.25/DanmakuFlameMaster/src/main/java), licensed under Apache-2.0. The source is compiled with `:app`.

Local changes: `ExternalMediaTimer` and `DrawHandler` separate media time from frame scheduling and report completed frames. External timers skip upstream idle sleep because its delay assumes 1× speed.
