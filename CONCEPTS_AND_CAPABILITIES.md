# Concepts and Capabilities — Reliable UDP Project

This document explains every Computer Networks concept used in this project and describes all of its capabilities. It is designed to help you understand *why* each technique exists, *how* this project implements it, and *what the project can do* end to end.

---

## Part 1: Concepts Used

### 1. UDP (User Datagram Protocol)

**What it is.**
UDP is a transport-layer protocol (Layer 4 of the OSI model) that sends self-contained messages called *datagrams* between hosts. Unlike TCP, UDP provides:

- **No guaranteed delivery** — a datagram can be silently lost.
- **No ordering** — datagrams may arrive in any order.
- **No duplicate protection** — the same datagram could arrive more than once.
- **No flow control** — the sender has no built-in way to slow down.
- **No congestion control** — UDP does not back off when the network is busy.

What UDP *does* provide is a **simple, low-overhead, connectionless** transport. Each datagram carries a source port, destination port, length, and a 16-bit checksum.

**Why this project uses UDP.**
The entire point of the project is to build a reliability layer *on top of* an unreliable transport. Using TCP would defeat the purpose. All application data in this project travels as UDP datagrams through Java's `DatagramSocket` and `DatagramPacket` APIs. TCP is never used.

---

### 2. Socket Programming

**What it is.**
Sockets are the programming interface for network communication. A *socket* is an endpoint for sending or receiving data over a network.

**How this project uses it.**
- The **server** creates a `DatagramSocket` bound to a specific UDP port and calls `socket.receive()` in a loop.
- The **client** creates a `DatagramSocket` (on an ephemeral port) and sends datagrams to the server's IP address and port.
- All communication happens through `DatagramPacket` objects — raw byte arrays with a destination address.

This is a direct, hands-on demonstration of the Berkeley Sockets API in Java.

---

### 3. Client-Server Architecture

**What it is.**
A network model where a *server* provides a service and a *client* initiates a request. They run as independent processes and communicate over the network.

**How this project uses it.**
- `Server.java` listens on a UDP port, accepts file-transfer requests, and saves received files.
- `Client.java` initiates a transfer session, reads a local file, and sends it to the server.
- They can run on the same machine (using `localhost`) or on two different machines connected over a LAN.

---

### 4. Custom Binary Protocol Design

**What it is.**
Instead of using an existing application-layer protocol (HTTP, FTP, etc.), this project defines its own *wire format* — the exact layout of bytes in every message exchanged between client and server.

**How this project does it.**
Every message is a **22-byte header** followed by an optional payload, encoded in big-endian (network byte order):

| Byte Offset | Size    | Field            | Purpose                                              |
|-------------|---------|------------------|------------------------------------------------------|
| 0           | 1 byte  | `version`        | Protocol version (must be `1`)                       |
| 1           | 1 byte  | `type`           | Message type (SYN, DATA, ACK, etc.)                  |
| 2           | 2 bytes | `window`         | Receiver's available buffer capacity                 |
| 4           | 4 bytes | `sessionId`      | Identifies the transfer session                      |
| 8           | 4 bytes | `seq`            | Sequence number of this packet                       |
| 12          | 4 bytes | `ack`            | Cumulative acknowledgement number                    |
| 16          | 2 bytes | `payloadLength`  | Length of the data after the header                   |
| 18          | 4 bytes | `checksum`       | CRC-32 for integrity verification                    |
| 22          | n bytes | `payload`        | Type-specific data (file chunks, metadata, etc.)     |

**Key design principle:** No Java serialisation is used. Every byte is explicitly read and written by `PacketCodec.java`, making the protocol language-agnostic and inspectable in Wireshark.

---

### 5. Packet Types and Message Semantics

The protocol defines **seven packet types**, inspired by TCP's control flags:

| Type       | Code | Purpose                                                                          |
|------------|------|----------------------------------------------------------------------------------|
| `SYN`      | 1    | **Initiate** a transfer — carries file name, size, and chunk size                |
| `SYN_ACK`  | 2    | **Confirm** session establishment — carries the server's receive window          |
| `DATA`     | 3    | **Carry a chunk** of the file being transferred                                  |
| `ACK`      | 4    | **Acknowledge** receipt of data — carries cumulative and selective info           |
| `FIN`      | 5    | **Signal completion** — the sender has transmitted all data                      |
| `FIN_ACK`  | 6    | **Confirm completion** — carries the server's SHA-256 hash and statistics        |
| `ERROR`    | 7    | **Report a failure** — carries an error code and human-readable message           |

These mirror the TCP SYN/ACK/FIN lifecycle but are simplified for a one-directional file transfer.

---

### 6. Session Management (Connection Establishment and Teardown)

**Concept: Handshake.**
Before any data can flow, the two sides must agree on the parameters of the transfer. TCP uses a 3-way handshake (SYN → SYN-ACK → ACK). This project uses a **2-message handshake**:

```
Client → Server:  SYN  (session ID, file name, file size, chunk size)
Server → Client:  SYN_ACK  (receive window size)
```

The session ID is a random integer chosen by the client. The server confirms it. A repeated SYN with the same parameters is answered idempotently (the same SYN_ACK is re-sent), so a lost SYN_ACK is recovered by simply retrying the SYN.

**Concept: Orderly Termination.**
After all DATA packets are acknowledged, the client sends `FIN`. The server responds with `FIN_ACK` (which carries the final SHA-256 hash). The server then keeps the session in a *linger* state so that if the `FIN_ACK` is lost, a repeated `FIN` from the client still gets answered — avoiding the "two generals" problem where the client falsely reports failure.

---

### 7. Sequence Numbers

**What they are.**
Each DATA packet is assigned a unique, incrementing number (starting from 0). The sequence number tells the receiver:

- **Which chunk** of the file this packet carries (file position = `seq * chunkSize`).
- **Whether a packet is missing** (a gap in the sequence).
- **Whether a packet is a duplicate** (same sequence number received twice).
- **The correct order** for reassembly.

**This project's approach:** Sequence numbers count *packets*, not bytes (unlike TCP, which uses byte offsets). This is simpler to understand and implement for fixed-chunk file transfer.

---

### 8. Acknowledgements (ACKs)

**What they are.**
When the receiver gets a DATA packet, it sends back an ACK to tell the sender "I got it." Without acknowledgements, the sender would never know if its data arrived.

**This project's dual ACK scheme:**
Each ACK carries *two* pieces of information:

1. **Cumulative `ack`** — "I have received everything up to (but not including) sequence number X in order." This means if an ACK is lost, the *next* ACK covers the same information. Lost ACKs are harmless.

2. **Selective `seq` echo** — "This ACK was specifically triggered by DATA packet number Y." This lets the sender know that a specific out-of-order packet arrived safely, even if there's a gap before it.

This combination provides the benefits of both *cumulative acknowledgement* (robustness against ACK loss) and *selective acknowledgement* (efficient retransmission of only what's missing).

---

### 9. Timeout and Retransmission

**What it is.**
If the sender sends a packet and doesn't receive an ACK within a certain time period (the *retransmission timeout* or RTO), it assumes the packet was lost and sends it again.

**How this project implements it:**
- Each in-flight DATA packet has its **own individual timer** (a deadline computed as `sendTime + timeoutMs`).
- When a timer expires, only **that specific packet** is retransmitted (not the entire window — this is Selective Repeat, not Go-Back-N).
- Each packet tracks how many times it has been retransmitted. If it exceeds `maxRetransmissions` (default: 10), the transfer is aborted with an error — the peer is considered unreachable.
- The timeout is configurable but **fixed** (not adaptive). This is a deliberate simplification; adaptive RTO (RFC 6298) is listed as a possible extension.

---

### 10. Sliding Window Protocol

**What it is.**
Instead of sending one packet and waiting for its ACK before sending the next (which is extremely slow, called *stop-and-wait*), the sender keeps a **window** of multiple packets that are allowed to be "in flight" simultaneously.

**The window rule in this project:**
```
A new packet can be sent if:
    nextSeq < totalPackets
    AND nextSeq < base + min(senderWindow, advertisedReceiverWindow)
```

Where:
- `base` = the lowest sequence number not yet acknowledged
- `nextSeq` = the next sequence number to send
- `senderWindow` = the sender's configured limit (default: 16)
- `advertisedReceiverWindow` = how much buffer space the receiver says it has

As ACKs arrive, `base` advances ("slides"), making room for new packets. This is why it's called a *sliding* window.

---

### 11. Selective Repeat vs. Go-Back-N

**Go-Back-N:** When a packet is lost, the sender retransmits *all* packets from the lost one onward, even those the receiver already has. Simple but wasteful.

**Selective Repeat:** When a packet is lost, the sender retransmits *only that specific packet*. The receiver buffers out-of-order packets and assembles them once the gap is filled. More complex but far more efficient.

**This project implements genuine Selective Repeat:**
- Each in-flight packet has its own timer.
- Only the expired packet is retransmitted.
- The receiver buffers out-of-order packets in a table and delivers them in order once the gap is filled.
- The receiver's window moves forward only when the *lowest expected* packet arrives.

This is verified by tests and the sequence diagram in the protocol documentation.

---

### 12. Flow Control

**What it is.**
A mechanism to prevent the sender from overwhelming the receiver. If the sender is faster than the receiver can process, packets pile up in the receiver's buffer and eventually get dropped.

**How this project does it.**
The receiver *advertises* its available buffer capacity in every ACK packet (the `window` field). The sender is not allowed to have more than `advertisedWindow` unacknowledged packets in flight. As the receiver delivers buffered packets, its advertised window grows; as it buffers more out-of-order packets, the window shrinks.

The design guarantees that the advertised window is always >= 1 (because the next expected packet is by definition not buffered), so zero-window probing is not needed — another simplification over TCP.

---

### 13. Receiver-Side Reassembly

**What it is.**
Since UDP delivers packets in any order (and the simulator can reorder them further), the receiver must reconstruct the original file from possibly out-of-order chunks.

**How this project does it.**
`ReliableReceiver.java` maintains:
- `rcvBase` — the next sequence number it expects in order.
- A buffer table for out-of-order packets within the receive window.

When a DATA packet arrives:
1. If `seq == rcvBase` → write to disk, advance `rcvBase`, then flush all consecutive buffered packets.
2. If `rcvBase < seq < rcvBase + capacity` → buffer it (out of order).
3. If `seq < rcvBase` → it's a duplicate; acknowledge but don't rewrite.
4. If `seq >= rcvBase + capacity` → outside the window; drop silently.

Data is written to a `.part` file during transfer and renamed only after all chunks are received and the size is verified.

---

### 14. Checksum and Error Detection (CRC-32)

**What it is.**
A checksum is a value computed from the contents of a packet that lets the receiver detect if the packet was corrupted in transit.

**CRC-32 in this project:**
Every packet includes a 4-byte CRC-32 checksum (computed over the header fields and the payload). On receipt, the receiver recomputes the CRC-32 and compares it. If they don't match, the packet is discarded.

**Properties of CRC-32:**
- Detects **all single-bit errors**.
- Detects **all burst errors up to 32 bits**.
- Nearly all random corruption (about 1 in 2^32 chance of a false pass).
- **Not cryptographic** — an attacker can modify a packet and recompute a valid CRC.

UDP itself has a 16-bit checksum, but on loopback it's often not verified, so the application-level CRC provides an additional safety net.

---

### 15. End-to-End Integrity Verification (SHA-256)

**What it is.**
SHA-256 is a cryptographic hash function that produces a 256-bit (32-byte) digest of any input. Even a single bit change in the input produces a completely different output.

**How this project uses it:**
1. Before sending, the client computes the SHA-256 of the source file.
2. After receiving all data, the server computes the SHA-256 of the reconstructed file **on disk**.
3. The server includes this hash in the `FIN_ACK` packet.
4. The client compares the two hashes.
5. If they match → the file was transferred perfectly. If not → there's a bug.

**Important distinction:** SHA-256 provides *integrity verification* only. It is not encryption (it doesn't hide the data) and not authentication (it doesn't prove who sent it).

---

### 16. Network Condition Simulation

**What it is.**
To test how the reliability protocol handles adverse conditions *without* needing a bad real-world network, the project includes a configurable simulator that applies impairments to actual protocol packets.

**What can be simulated:**

| Impairment   | What it does                                                  | Flag                |
|--------------|---------------------------------------------------------------|---------------------|
| Packet loss  | Randomly drops outgoing packets (they never reach the OS)     | `--loss <0..1>`     |
| Delay        | Holds packets for a specified duration before sending         | `--delay <ms>`      |
| Jitter       | Adds random additional delay (0 to jitter ms)                 | `--jitter <ms>`     |
| Corruption   | Flips a random bit in the packet (caught by CRC-32)           | `--corrupt <0..1>`  |
| Duplication  | Sends the same packet twice                                   | `--dup <0..1>`      |

**Critical point:** These impairments are applied to *real packets* in the application layer. Dropped packets are genuinely never sent — they don't merely appear lost in the UI.

A `--seed` parameter makes experiments reproducible. A deterministic drop-rule hook allows tests to create *exact* failure scenarios (e.g., "lose the first two FIN_ACK packets").

---

### 17. Round-Trip Time (RTT) Estimation

**What it is.**
The RTT is the time between sending a packet and receiving its acknowledgement. Knowing the RTT helps the sender choose a good retransmission timeout.

**How this project estimates it:**
- **Exponentially Weighted Moving Average (EWMA):** `rttEstimate = (1 - alpha) * rttEstimate + alpha * sample`, with alpha = 1/8 (same as TCP).
- **Karn's Rule:** RTT samples are *not* taken from retransmitted packets, because you can't tell whether the ACK is for the original or the retransmission.
- The estimated RTT is recorded in metrics but **not fed back** into the timeout (the timeout is fixed). Adaptive RTO is listed as an extension.

---

### 18. Performance Metrics and Measurement

**What it is.**
Quantitative data collected during a transfer to evaluate the protocol's performance.

**Metrics collected:**

| Metric                 | Definition                                                                             |
|------------------------|----------------------------------------------------------------------------------------|
| **Duration**           | Wall-clock time from SYN to FIN_ACK receipt                                            |
| **Throughput**         | Total data bytes sent (including retransmissions) / duration. In bytes/s and Mbps       |
| **Goodput**            | Useful file bytes delivered / duration. Goodput <= Throughput when there are retransmits |
| **Retransmissions**    | Number of DATA packets that were sent more than once                                   |
| **Timeouts**           | Number of timer expirations                                                            |
| **Simulated drops**    | Packets the simulator discarded                                                        |
| **Duplicate packets**  | Packets the server received more than once                                             |
| **Out-of-order**       | Packets the server buffered because they arrived before the expected one               |
| **RTT (estimated)**    | Mean, min, max of the round-trip time samples                                          |
| **Integrity**          | Whether the source and destination SHA-256 hashes match                                |

All values are **actually measured** by the running program, not hardcoded or fabricated. They are exported to CSV files with timestamps.

---

### 19. Wireshark and Packet Capture

**What it is.**
Wireshark is a network protocol analyser that captures packets on a network interface and lets you inspect them.

**How this project uses it:**
Since the protocol is custom, Wireshark shows the packets as raw UDP payloads. The project documentation explains how to:

- Capture on the loopback interface.
- Filter by UDP port (`udp.port == 5000`).
- Read the custom header bytes: byte 0 = version, byte 1 = type (1=SYN, 3=DATA, 4=ACK, etc.).
- Identify the handshake, data transfer, retransmissions, and termination in the packet list.
- Verify the protocol uses UDP (not TCP).

---

### 20. Path Traversal Protection

**What it is.**
A security concept where a server rejects file names that could trick it into writing outside the intended directory (e.g., `../../etc/passwd`).

**How this project implements it.**
`SafePaths.java` enforces:
- File names must match a whitelist pattern (letters, numbers, dots, underscores, hyphens, spaces, parentheses).
- No `..` sequences, no path separators (`/` or `\`), no drive letters (`:`).
- No Windows reserved device names (`CON`, `NUL`, `COM1`, etc.).
- Existing files are never overwritten — a conflict creates `name (1).ext`, `name (2).ext`, etc.
- The resolved path is verified to be a **direct child** of the downloads directory.

---

## Part 2: Project Capabilities

### What This Project Can Do

#### 1. Reliable File Transfer Over UDP
Transfer any file (text, binary, PDF, images, videos) from one machine to another using a custom reliability protocol. The file arrives intact despite using unreliable UDP as the transport.

#### 2. Automatic Error Recovery
- **Lost packets** are detected via timeout and automatically retransmitted.
- **Duplicate packets** are detected and discarded.
- **Out-of-order packets** are buffered and reassembled correctly.
- **Corrupted packets** are detected by CRC-32 and rejected.
- **Lost acknowledgements** are covered by cumulative ACKs.
- **Lost final ACK** is handled by the server's linger mechanism.

#### 3. Configurable Network Simulation
Simulate real-world network conditions without needing a bad network:
- Set any packet loss rate (e.g., 10% loss: `--loss 0.10`).
- Add artificial delay (e.g., 50ms: `--delay 50`).
- Add random jitter for realistic variability.
- Simulate bit-level corruption.
- Simulate packet duplication.
- Use deterministic seeds for reproducible experiments.

#### 4. End-to-End Integrity Verification
Every transfer automatically computes and compares SHA-256 hashes of the source file and the received file. A mismatch means there's a bug — the protocol guarantees delivery integrity.

#### 5. Performance Benchmarking
Run automated experiments across multiple network conditions:
```
Baseline      -> 0% loss,  0ms delay
Low loss      -> 5% loss,  10ms delay
Moderate loss -> 10% loss, 25ms delay
High delay    -> 0% loss,  100ms delay
Combined      -> 10% loss, 100ms delay
```
Results are exported to CSV with measured throughput, goodput, retransmissions, RTT, and integrity status.

#### 6. Detailed Logging and Metrics
- Real-time console output showing every packet (in `--verbose` mode).
- Transfer summaries with throughput, retransmission counts, and timing.
- CSV export of every transfer for analysis and comparison.
- Server-side and client-side metrics captured independently.

#### 7. Cross-Machine Transfer (LAN)
Works across two computers on the same network:
1. Start the server on Machine A.
2. Open UDP port in the firewall.
3. Run the client on Machine B pointing to Machine A's IP.

#### 8. Comprehensive Test Suite
70 automated tests covering:
- Clean transfers (no loss).
- Transfers under various loss/delay scenarios.
- Duplicate and out-of-order packet handling.
- Invalid/malformed packet rejection.
- File integrity verification.
- Empty and small file edge cases.
- Large files requiring many windows.
- Server unavailable scenarios.
- Maximum retry limit enforcement.
- Lost handshake and termination packets.

#### 9. Optional Graphical Interface
A Swing-based GUI (`--gui`) providing:
- Visual configuration of all transfer parameters.
- Real-time progress bar.
- Live log of protocol events.
- Point-and-click file selection.

#### 10. Security Protections
- Path traversal defence prevents malicious file names.
- File size limits prevent storage exhaustion.
- Session validation prevents packet injection from other addresses.
- No-overwrite policy prevents existing files from being replaced.
- Idle timeout cleans up abandoned sessions and deletes partial files.

---

## Part 3: How the Concepts Connect (Layered Architecture)

```
+--------------------------------------------------------------------+
|                     APPLICATION LAYER                              |
|                                                                    |
|  File Transfer Logic (Client.java / Server.java)                  |
|       |                                                            |
|       v                                                            |
|  Custom Reliability Protocol (transport/)                         |
|    - Session management (SYN/SYN_ACK handshake)                   |
|    - Sequence numbers (each DATA chunk numbered)                  |
|    - ACKs (cumulative + selective)                                |
|    - Sliding window (multiple packets in flight)                  |
|    - Selective Repeat (retransmit only the lost one)              |
|    - Flow control (receiver advertises capacity)                  |
|    - Reassembly (buffer out-of-order, deliver in order)           |
|    - Orderly close (FIN/FIN_ACK with linger)                     |
|       |                                                            |
|       v                                                            |
|  Custom Packet Format (protocol/)                                 |
|    - 22-byte binary header (big-endian)                           |
|    - CRC-32 per-packet integrity                                  |
|    - Explicit encode/decode (no Java serialisation)               |
|       |                                                            |
|       v                                                            |
|  Network Simulation (simulation/)                                 |
|    - Loss, delay, jitter, corruption, duplication                 |
|    - Applied to real outgoing packets                             |
|       |                                                            |
|       v                                                            |
|  Metrics and Logging (metrics/, util/)                            |
|    - RTT estimation (EWMA, Karn's rule)                           |
|    - Throughput, goodput, retransmission counts                   |
|    - SHA-256 end-to-end integrity                                 |
|    - CSV export                                                    |
|                                                                    |
+--------------------------------------------------------------------+
|                     TRANSPORT LAYER                                |
|                                                                    |
|  UDP (DatagramSocket / DatagramPacket)                            |
|    - Unreliable, connectionless, no ordering                      |
|    - 16-bit checksum (often not verified on loopback)             |
|                                                                    |
+--------------------------------------------------------------------+
|                     NETWORK LAYER                                  |
|                                                                    |
|  IP (IPv4)                                                        |
|    - Addressing (localhost for loopback, LAN IP for cross-host)  |
|    - Fragmentation if chunk > ~1450 bytes                         |
|                                                                    |
+--------------------------------------------------------------------+
```

---

## Part 4: Quick Concept-to-Code Map

| Concept                    | Where in the Code                                    |
|----------------------------|------------------------------------------------------|
| UDP socket programming     | `Client.java`, `Server.java`                         |
| Custom packet format       | `protocol/Packet.java`, `protocol/PacketCodec.java`  |
| Packet types               | `protocol/PacketType.java`                           |
| Session handshake          | `transport/ReliableSender.java` (client), `Server.java` (server) |
| Sequence numbers           | `transport/SlidingWindow.java`                       |
| Acknowledgements           | `transport/ReliableReceiver.java`                    |
| Timeout and retransmission | `transport/ReliableSender.java`                      |
| Sliding window             | `transport/SlidingWindow.java`                       |
| Selective Repeat           | `transport/ReliableSender.java` + `ReliableReceiver.java` |
| Flow control               | Advertised window in ACK packets                     |
| Reassembly                 | `transport/ReliableReceiver.java`                    |
| CRC-32 error detection     | `protocol/PacketCodec.java`                          |
| SHA-256 integrity          | `util/FileChecksum.java`                             |
| Network simulation         | `simulation/NetworkConditions.java`                  |
| RTT estimation             | `metrics/TransferMetrics.java`                       |
| Performance metrics        | `metrics/TransferMetrics.java`, `MetricsLogger.java` |
| Path traversal protection  | `util/SafePaths.java`                                |
| Logging                    | `util/Logger.java`                                   |
| CLI argument parsing       | `util/ArgParser.java`                                |
| Experiment automation      | `ExperimentRunner.java`                              |

---

## Part 5: Summary — What Makes This Project Non-Trivial

1. **It solves a real problem** — reliable delivery over an unreliable channel — using the same fundamental ideas that TCP uses (but implemented from scratch, not borrowed from the OS).

2. **It implements Selective Repeat correctly** — not a simplified Go-Back-N masquerading as Selective Repeat. Each packet has its own timer; only the lost packet is retransmitted; the receiver buffers and reorders.

3. **It handles edge cases** — lost handshakes, lost final ACKs, duplicate SYNs mid-transfer, malformed packets, empty files, path traversal, server crashes, and unreachable peers.

4. **Everything is measured, not faked** — metrics come from real protocol execution, experiments use real UDP sockets (even on loopback), and the test suite runs the actual server and client.

5. **It is inspectable** — the binary wire format can be read byte-by-byte in Wireshark, the code is logically separated by concern, and every design decision is documented with rationale.
