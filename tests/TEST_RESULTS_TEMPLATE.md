# Test results template

Fill this in from **your own** runs. Do not copy numbers from anywhere else.

Environment: OS ______  Java ______  CPU ______  Date ______  Commit/zip ______

## A. Automated tests (`mvn test` or `scripts/run-tests.sh`)

Total tests: ____  Passed: ____  Failed: ____  Run time: ____ s

| # | Required scenario | Test method(s) (class) | Result | Notes |
|---|---|---|---|---|
| 1 | No simulated loss | `transferWithNoLoss` (TransferIntegrationTest) | | |
| 2 | 5% loss | `transferWith5PercentLoss` | | |
| 3 | 10% loss | `transferWith10PercentLoss` | | |
| 4 | Artificial delay | `transferWithArtificialDelay` | | |
| 5 | Loss + delay | `transferWithLossAndDelayCombined` | | |
| 6 | Duplicate DATA | `duplicateDataPacketsAreAckedButWrittenOnlyOnce` (ServerBehaviourTest), `duplicatedPacketsDoNotCorruptTheFile` | | |
| 7 | Out-of-order DATA | `outOfOrderPacketsAreBufferedAndWrittenInOrder`, `jitterReordersPacketsAndReceiverReassemblesThem`, ReliableReceiverTest | | |
| 8 | Invalid / truncated packets | PacketCodecTest, `garbageAndTruncatedDatagramsDoNotCrashTheServer`, `dataWithWrongLengthOrSequenceIsIgnored` | | |
| 9 | File integrity | `integrityDigestsAreReportedAndCompared`, `corruptedPacketsAreDetectedAndRecovered`, UtilTest | | |
| 10 | Empty and small files | `emptyAndSmallFiles` | | |
| 11 | Many windows | `largeFileSpanningManyWindows` | | |
| 12 | Server unavailable | `unreachableServerEndsWithClearError` (FailureHandlingTest) | | |
| 13 | Retransmission limit | `retransmissionLimitIsEnforcedAndSenderRespectsItsWindow` | | |
| 14 | Invalid session ids | `invalidSessionIdentifiersAreRejected` | | |
| 15 | Lost final ACKs | `lostFinalAcknowledgementsDoNotCauseFalseFailure`, `lostAckForTheLastDataPacketIsRecovered` | | |

## B. Manual two-process test (server and client started separately)

| Run | Command | Result | SHA-256 match? | Retransmissions | Duration |
|---|---|---|---|---|---|
| Baseline | `java -cp out/main reliableudp.Client localhost 5000 sample.txt` | | | | |
| 5% loss | `... --loss 0.05 --seed 1` | | | | |
| LAN (2 PCs) | `... <SERVER_IP> 5000 sample.txt` | | | | |

## C. Performance experiment (`scripts/run-experiment.sh`)

CSV file produced: ______________________  File size: ______ bytes  Repeats: ____  Window: ____  Chunk: ____

| Scenario | Loss | One-way delay | Mean duration (s) | Mean goodput (Mbps) | Mean retransmissions | RTT est. (ms) | Integrity OK |
|---|---|---|---|---|---|---|---|
| baseline | 0% | 0 ms | | | | | |
| low-loss | 5% | 10 ms | | | | | |
| moderate-loss | 10% | 25 ms | | | | | |
| high-delay | 0% | 100 ms | | | | | |
| combined | 10% | 100 ms | | | | | |

Observations (write what YOUR numbers show, then compare with the expected trends in the README):

## D. Wireshark checks

- [ ] Capture shows UDP only (no TCP) on the chosen port
- [ ] SYN / SYN_ACK seen at the start (type bytes 01 / 02)
- [ ] DATA (03) and ACK (04) packets seen; retransmitted DATA with the same sequence number seen under loss
- [ ] FIN (05) / FIN_ACK (06) seen at the end
