# Five-minute demonstration script

Preparation (before the viva): build (`./scripts/build.sh`), have a text file ready (such as `sample.txt`),
open three terminals (server, client, sha256/Wireshark) and Wireshark capturing `lo` with filter `udp port 5000`.

| Time | Do | Say |
|---|---|---|
| 0:00–0:30 | Show the folder layout and `docs/PROTOCOL.md` header table | "UDP has no reliability. I built it above `DatagramSocket`: sequence numbers, ACKs, timers, a sliding window, Selective Repeat. Not TCP, no congestion control." |
| 0:30–1:15 | Terminal 1: `java -cp out/main reliableudp.Server 5000`. Terminal 2: `java -cp out/main reliableudp.Client localhost 5000 sample.txt` | "Two independent processes. SYN / SYN_ACK establishes a session id; the progress lines are acknowledged bytes." |
| 1:15–2:00 | Show the summary: SUCCESS, retransmissions 0, SHA-256 source = received. Run `sha256sum sample.txt downloads/sample.txt` (Windows: `certutil -hashfile sample.txt SHA256`) | "Integrity is checked end to end; the server hashes the file it wrote to disk." |
| 2:00–3:15 | Rerun with `--loss 0.10 --delay 20 --seed 3 --timeout 100` (and the server started with the same `--loss/--delay`). Point at `TIMEOUT seq=… -> retransmission` lines and at the summary: drops, retransmissions, RTT, duplicates, out-of-order | "The simulator really discards packets before they reach the socket. Only the missing packet is resent – that is Selective Repeat. The receiver buffered later packets and reordered them." Use `--verbose` briefly to show `[sim] DROP`. |
| 3:15–4:00 | Wireshark: filter `udp.port == 5000`, then `tcp` (empty). Show SYN (`01`), DATA (`03`), ACK (`04`), a repeated `seq`, FIN/FIN_ACK | "Wireshark shows plain UDP; I decode the header by hand. Dropped packets are absent because the simulator drops them before the OS." |
| 4:00–4:30 | `./scripts/run-tests.sh` (already compiled; show the 70-test summary) or show `FailureHandlingTest.lostFinalAcknowledgementsDoNotCauseFalseFailure` | "Tests drive the real sender and receiver, including lost final ACK, unreachable server, retry limit, bad sessions, path traversal." |
| 4:30–5:00 | `./scripts/run-experiment.sh --repeats 1` output table / CSV | "Every number is measured. Expected trends: loss → retransmissions and time; delay → lower goodput because the window is fixed. Limitations: no congestion control, fixed RTO, no encryption." |

Fallbacks: if the LAN is unavailable use loopback; if a demo transfer is slow because of high loss, lower `--loss` or use a smaller file.

---

# Viva questions with concise answers

**1. Why is UDP unreliable, and what exactly did you add?**
UDP may lose, duplicate, delay or reorder datagrams and gives no feedback. I added sequence numbers, ACKs, timeout-based retransmission, a sliding window with flow control, duplicate/out-of-order handling, an orderly close, and end-to-end SHA-256 verification.

**2. Which sliding-window algorithm did you implement and how do you know it is not Go-Back-N?**
Selective Repeat. Each in-flight packet has its own timer and only the expired packet is resent (`SlidingWindow.expired`, `ReliableSender` step c); the receiver buffers out-of-order packets (`ReliableReceiver`). In my lossy runs the retransmission count stays close to the number of lost packets rather than multiples of the window size (compare `retransmissions` with `simulated_dropped` in the CSV), and `SlidingWindowTest` checks the bookkeeping.

**3. What is a sequence number here?** A packet index starting at 0 (not a byte offset as in TCP). DATA `seq` = chunk number; file position = `seq × chunkSize`.

**4. Are your ACKs cumulative or selective?** Both. `ack` = next in-order sequence number needed (cumulative); `seq` echoes the DATA packet that caused the ACK (selective). Lost ACKs are covered by later cumulative ones; the echo identifies which packet the sender may stop retransmitting and gives an unambiguous RTT sample.

**5. Explain the window.** Sender may send `seq` in `[base, base + min(windowSize, advertisedWindow))`, where `base` is the lowest unacknowledged packet. Window size 1 = stop-and-wait; larger values keep the pipe full: ideal throughput ≈ `window × chunk / RTT`.

**6. What is the advertised window and can it be zero?** `capacity − bufferedOutOfOrderPackets`, carried in every ACK. It is never 0 because the next expected packet is never buffered, so it is always acceptable; hence no window probes are needed.

**7. How are duplicates detected?** `seq < rcvBase` (already delivered) or `seq` already in the reorder buffer. Duplicates are counted, re-ACKed (the earlier ACK may have been lost) and never written twice (`duplicateDataPacketsAreAckedButWrittenOnlyOnce`).

**8. How is timeout chosen? What if it is wrong?** A fixed, configurable value. Too small → spurious retransmissions (duplicates); too large → slow loss recovery. TCP uses an adaptive RTO (RFC 6298). I measure RTT (EWMA, Karn's rule) but do not feed it back — a listed extension.

**9. Why Karn's rule?** If a retransmitted packet is ACKed, you cannot tell whether the ACK answers the original or the copy, so the sample would be ambiguous. Retransmitted packets are never sampled.

**10. What happens when the final ACK (FIN_ACK) is lost?** The client's FIN timer expires and FIN is repeated. The server keeps finished sessions for a linger period and repeats the stored FIN_ACK instead of forgetting the session (`lostFinalAcknowledgementsDoNotCauseFalseFailure`).

**11. What if the server disappears?** Each packet has a retry limit (`--retries`). Exceeding it aborts with a clear error ("peer unreachable"); SYN has the same limit; the server aborts idle sessions and deletes partial files.

**12. How do you prevent a corrupted packet being accepted?** CRC-32 over header + payload; mismatches are discarded and counted, and the sender retransmits after its timer. CRC-32 is not cryptographic. UDP's own 16-bit checksum also exists.

**13. Is SHA-256 encryption or authentication?** No. It is an integrity check of the whole file after reassembly; anyone able to alter the file and the digest could defeat it. It verifies that what arrived equals what was sent.

**14. Why a per-packet CRC and a whole-file SHA-256?** CRC catches damage per packet cheaply so the packet can be retransmitted; SHA-256 independently verifies the reconstruction (reordering bugs, disk errors, missing data).

**15. How do you stop path traversal?** The server never uses the client's string as a path: a character whitelist, no `..`, no reserved device names, creation with `CREATE_NEW` inside the downloads directory, a check that the result is a direct child, and no overwriting (`name (1).ext`). Tested with `../x`, `..\x`, `/etc/passwd`, `C:\x`.

**16. How is the session identified and is it secure?** A random 31-bit id chosen by the client, bound to the client's IP/port at the server. It stops accidental mix-ups and simple hijacks from another port, but it is not a secret on a shared network — there is no authentication or encryption.

**17. How does the simulator work and how do you know the loss is real?** `NetworkConditions.send` encodes the packet, draws from a seeded PRNG, and either discards it, delays it via a scheduler, flips a bit, or sends it twice. A test sends 400 datagrams to a real socket and asserts that exactly `sent − dropped` arrive. Simulated loss is distinct from real loss, which would appear in no counter of the simulator.

**18. Why do results vary between runs with the same seed?** The seed fixes the sequence of random decisions, but which packet is "the n-th send" depends on timing (timers, scheduling), so runs are repeatable statistically, not bit-for-bit.

**19. Difference between throughput and goodput in your CSV?** Throughput counts all DATA payload bytes transmitted including retransmissions; goodput counts only file bytes delivered intact (and only on verified success). With losses goodput < throughput.

**20. Why does delay reduce goodput even with 0 % loss?** The sender can have only `window` packets in flight per RTT; with a fixed window, throughput ≈ `window × chunk / RTT`, so a larger RTT lowers it. This is the bandwidth-delay-product effect.

**21. Why does loss hurt so much?** The window can't slide past a missing packet until its timer fires, so each loss can stall the sender for about one RTO once the window is full.

**22. What would you add to approach TCP?** Adaptive RTO, fast retransmit from duplicate ACKs, congestion control (slow start, AIMD), selective-ACK bit-maps, connection teardown hardening, encryption (DTLS-like), path-MTU awareness.

**23. Why is the handshake two messages and not three like TCP?** TCP's third message protects against old duplicate SYNs and sets sequence numbers independently on both sides. Here data flows one way, sequence numbers start at 0, and the first DATA packet implicitly completes the handshake; repeated SYNs are handled idempotently. It is a deliberate simplification.

**24. What is the maximum chunk size and why 1024 by default?** 65 485 bytes (UDP limit minus header), but anything above ~1450 bytes is fragmented by IP on Ethernet, and the loss of one fragment loses the whole datagram. 1024 stays well below the MTU.

**25. Why single-threaded sender and server?** Simplicity and correctness: no locks, events processed in a deterministic order. Only the simulator's delay scheduler uses another thread, and it only calls `socket.send`.
