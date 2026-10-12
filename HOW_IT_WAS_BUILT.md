# How the Reliable UDP project was built

This is an honest build log: the order things were done in, the decisions taken and why, how each part was
checked, and what went wrong along the way. It is meant to help you explain the project in your viva and to
rebuild or extend it yourself.

---

## 1. Approach in one paragraph

I designed the wire format and the reliability algorithm on paper first, then wrote the code from the bottom up
(bytes → packets → sliding-window logic → sockets → CLI), keeping the protocol logic free of sockets wherever
possible so that it could be unit-tested. After the code worked end to end between two real processes, I wrote
the test suite, deliberately broke the code to check that the tests could fail, ran a packet capture to verify
the Wireshark documentation, and only then wrote the documentation from what the code actually does.

## 2. Environment and tooling

| Item | What happened |
|---|---|
| Java | The sandbox had only a JRE (`javac` was missing). I installed `openjdk-21-jdk-headless` with `apt`. |
| JUnit | The GitHub release URL for the console launcher returned 404. `apt install junit5` provided `junit-platform-console-standalone.jar` and the Jupiter jars instead. |
| Maven | Not usable: Maven Central is not reachable from the sandbox. `pom.xml` is therefore **written but never run**. The tested route is `javac` + JUnit console launcher. |
| Packet capture | `apt install tcpdump`, then captured on `lo`. Wireshark itself was not available. |
| Shell | Commands ran under `/bin/sh` (dash), not bash. This caused two small mistakes (section 7). |

## 3. Design decisions made before coding

| Decision | Alternatives considered | Why this one |
|---|---|---|
| **Selective Repeat** | Go-Back-N | The brief prefers it and forbids claiming it unless it is real. Each packet has its own timer and only the expired packet is resent. |
| Sequence numbers count **packets**, not bytes | TCP-style byte offsets | Simpler to explain; file position = `seq × chunkSize`. |
| ACK carries **both** cumulative `ack` and echoed `seq` | Cumulative only; SACK bit-map | Lost ACKs are covered by later cumulative ones; the echo gives selective information and an unambiguous RTT sample, with no extra fields. |
| Header of 22 bytes, CRC-32 over header + payload | Plain UDP checksum only; SHA-256 per packet | Detects corruption the simulator injects; cheap. UDP's own checksum is often not verified on loopback. |
| Receiver advertises `capacity − buffered` | Fixed window | Real flow control. It can never be 0, because the next expected packet is never buffered, so no zero-window probing is needed. |
| Client-chosen random session id, server confirms | Server-assigned id | Lets a repeated SYN be recognised and answered idempotently. Keyed by peer address + id. |
| 2-message handshake (SYN, SYN_ACK) | TCP-style 3-way | Data flows one way and sequence numbers start at 0, so the first DATA packet implicitly confirms it. A deliberate simplification. |
| FIN → FIN_ACK plus a **linger** period on the server | Server forgets the session at once | Fixes the brief's "lost final ACK must not cause a false failure" requirement. |
| Fixed, configurable retransmission timeout | Adaptive RTO | Keeps the algorithm explainable. RTT is measured (EWMA, Karn's rule) but not fed back. Listed as an extension. |
| Single-threaded sender and server event loops | Thread per session | No locks, deterministic ordering. Only the simulator's delay scheduler uses another thread. |
| Impairment simulator sits in front of `socket.send` | UI-only display | The brief requires that loss/delay act on real packets. Packets it drops never reach the OS. |
| Deterministic **drop-rule hook** in the simulator | Rely on random loss in tests | Lets tests express exact failures such as "the first two FIN_ACKs are lost". |
| Receiver logic with no socket (`ReliableReceiver.onData(packet) → ack`) | Logic inside `Server` | Unit-testable in isolation. |

## 4. Build order

The brief asked for ten phases. I followed the order, but phases 3–8 depend on each other (the sender needs the
simulator and metrics to compile), so I wrote those classes as one batch and then validated them step by step.

| Step | Work | Immediate check |
|---|---|---|
| 1 | Project skeleton, `protocol/` package: `PacketType`, `ErrorCode`, `Packet`, `PacketCodec`, `ProtocolConfig`, `TransferInfo`, `FinalReport`, `FileChecksum` | — |
| 2 | `PacketCodecTest` (14 tests) | Compiled and ran: all green before any networking code existed |
| 3 | Utilities (`Logger`, `ArgParser`, `SafePaths`), `NetworkConditions`, metrics classes | Compiled |
| 4 | `SlidingWindow`, `ReliableReceiver`, `TransferSession`, `ReliableSender` | Compiled with `-Xlint:all`, no warnings |
| 5 | `Server`, `Client`, optional `ClientGui` | Compiled |
| 6 | **Smoke test with two OS processes**: 300 KB random file, no loss, then 10% loss + 20 ms delay both ways | SHA-256 of source and received file identical |
| 7 | Bug found in review: a duplicated/delayed SYN mid-transfer would have produced an ERROR and aborted a healthy session. Fixed by answering repeated identical SYNs idempotently | Covered by `duplicateSynIsAnsweredIdempotentlyEvenMidTransfer` |
| 8 | Test suite grew to 70 tests (integration, raw-socket server tests, failure tests, unit tests) | All pass |
| 9 | `ExperimentRunner` (5 scenarios, CSV + summary table) | Ran it; durations matched what the delay and window predict |
| 10 | Documentation | Written last, from the finished code |

## 5. How correctness was verified

1. **Unit tests of pure logic**: codec, `SlidingWindow`, `ReliableReceiver`, `SafePaths`, SHA-256 known vectors.
2. **Integration tests with the real classes** over UDP on loopback, with the server on an ephemeral port. After every
   successful transfer the test re-hashes the file the server wrote and compares bytes with the source.
3. **Hand-crafted packets** from a raw `DatagramSocket` against the real server, to produce exact duplicates,
   reordering, wrong lengths, invalid sessions, early FIN and garbage datagrams.
4. **Mutation checks**: I deliberately broke the code and confirmed the suite failed.
   - Removing the "already delivered" check made the server write duplicates → 6 integration tests failed.
   - Making the server forget finished sessions → lost-FIN_ACK tests failed (`UNKNOWN_SESSION`).
   - Making the sender ignore the advertised window → the window-obedience test failed (32 packets sent instead of 3).
   The sources were restored afterwards and the suite re-run green.
5. **Flakiness check**: the full suite was run 5 times in a row; 70/70 each time.
6. **Packet capture**: client and server ran while `tcpdump -i lo` recorded the traffic. It confirmed the header bytes
   (`01 01 …` for version 1 / SYN), the SYN length (22 + 14 + name length), the type offset (frame byte 43 on Linux `lo`),
   and the SYN → SYN_ACK → DATA/ACK (with retransmissions) → FIN → FIN_ACK sequence.
7. **CLI behaviour**: unknown option rejected, unreachable server gives a clear failure, exit codes as documented.

## 6. Requirement → where it is implemented

| Brief requirement | Implementation | Proof |
|---|---|---|
| Handshake and session id | `ReliableSender.send`, `Server.onSyn` | `lostSynAckIsRecoveredByRepeatingTheSyn` |
| Sequence numbers, duplicates, reordering | `ReliableReceiver.onData` | `duplicate…`, `outOfOrder…` tests |
| Timeout and retry limit | `ReliableSender.sendData` step (c) | `retransmissionLimitIsEnforced…` |
| Sliding window + receiver capacity | `SlidingWindow`, advertised window in ACKs | `senderObeysReceiversAdvertisedWindow` |
| Orderly close, lost final ACK | FIN/FIN_ACK, `Server.onFin` + linger | `lostFinalAcknowledgementsDoNotCauseFalseFailure` |
| SHA-256 integrity | `Server.onFin` hashes the file on disk; `Client.transfer` compares | `integrityDigestsAreReportedAndCompared` |
| Loss, delay, jitter, corruption, seed | `NetworkConditions` | `NetworkConditionsTest` |
| Path traversal protection | `SafePaths` | `unsafeFileNamesAreRejected…` |
| Metrics and CSV | `TransferMetrics`, `MetricsLogger` | `csvExportContainsMeasuredValues` |
| Experiment runner | `ExperimentRunner` | run once, outputs inspected |

## 7. Problems I hit (and what they taught)

| Problem | Cause | Fix |
|---|---|---|
| Source directories were created with literal names like `{protocol,transport,…}` and the first batch of files failed to write | `/bin/sh` does not expand `{a,b}` | Removed the stray directory, created each directory separately, rewrote the files |
| Shell tool call hung/died twice during the smoke test | `pkill -f "reliableudp.Server"` also matched my own shell command line, killing it | Started the server with `&` and killed it by PID (`$!`) |
| `javac` not found | Only a JRE was installed | Installed the JDK with `apt` |
| JUnit download 404 | Release URL moved | Used the `junit5` apt package |
| Duplicate SYN aborted a healthy session | First draft treated any repeated SYN on an active session as a conflict | Compare parameters; identical → resend SYN_ACK, different → `SESSION_EXISTS` (see step 7 above) |
| Two meaningless assertions in my own tests (convoluted ternaries that could never fail) | Sloppy writing under time pressure | Replaced with real assertions; this is also why I added the mutation checks, since a green suite on its own proves little |

## 8. Deviations from the brief and honest gaps

- **Tests live in `src/test/java`** (Maven convention), not in `tests/`. The `tests/` folder holds the results template.
- **Extra files beyond the suggested structure**: `ErrorCode`, `TransferInfo`, `FinalReport`, `PacketFormatException`,
  `TransferResult`, `TransferException`, `ProgressListener`, `SafePaths`, `ArgParser`, `ClientGui`, `ExperimentRunner`.
  Each has a working implementation and a purpose listed in the README.
- **The optional GUI was compiled but never run** (no display available). It contains no protocol logic.
- **`pom.xml`, Windows `.bat` scripts and macOS/Windows loopback offsets are unverified** in my environment.
- **The Wireshark display filters** (`udp.payload[...]`) were not tried in Wireshark; only the byte offsets were verified with tcpdump.
- **No experiment results are quoted in the docs.** I ran the experiment once in a one-CPU container to check that the
  runner works; those numbers are not representative of your machine. Run it yourself and fill in
  `tests/TEST_RESULTS_TEMPLATE.md`.
- **Protocol limitations** (fixed timeout, no congestion control, no encryption/authentication, packet-based 32-bit
  sequence numbers, simulator only on outgoing packets) are listed in README section 10.

## 9. Suggested reading order for your viva preparation

1. `docs/PROTOCOL.md` sections 1 and 4: the header and the Selective Repeat tables.
2. `protocol/PacketCodec.java`: how bytes become a packet and what is validated.
3. `transport/SlidingWindow.java`: the sender's bookkeeping (about 150 lines).
4. `transport/ReliableReceiver.java`: the receiver table from PROTOCOL.md as code.
5. `transport/ReliableSender.java`: `sendData` (fill window → wait for ACK → retransmit expired).
6. `Server.java`: `onSyn`, `onData`, `onFin`.
7. `simulation/NetworkConditions.java`: how loss and delay are really applied.
8. Tests: `ServerBehaviourTest` (shows the protocol packet by packet) and `FailureHandlingTest`.

A good way to prepare is to ask for a walk-through of one stage at a time, then change one parameter
(window size, timeout, loss rate) and predict the effect on retransmissions and duration before running it.

## 10. Reproducing the checks yourself

```sh
./scripts/build.sh                       # compile
./scripts/run-tests.sh                   # 70 tests (needs JUNIT_CONSOLE or the apt junit5 package)
./scripts/run-experiment.sh --repeats 3  # measured CSV + summary table in logs/
```

To repeat the mutation check, break one behaviour on purpose (for example change `if (seq < rcvBase)` in
`ReliableReceiver.onData` to `if (false)`), re-run the tests, and confirm that they fail. Then restore the line.
