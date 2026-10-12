# Project Brief: Custom Reliable Transport Protocol over UDP

## Project objective

Build a complete, working, well-documented Computer Networks mini-project titled:

**Design and Implementation of a Custom Reliable Transport Protocol over UDP**

UDP does not guarantee packet delivery, ordering, or duplicate protection. The objective is to implement a custom reliability layer on top of UDP that provides reliable message and file transfer using concepts inspired by TCP.

The project must demonstrate:

- UDP socket programming
- Client-server communication
- Sequence numbers and acknowledgements
- Retransmission after timeout
- Sliding-window flow control
- Correct packet ordering and duplicate handling
- File transfer and integrity verification
- Performance measurement under simulated packet loss and delay
- Packet-level verification using Wireshark

This is an educational protocol implementation, not a replacement for production TCP.

## 1. Technology requirements

Use:

- Java 17 or later
- Standard Java networking APIs: `DatagramSocket`, `DatagramPacket`, and related classes
- Java Swing for a simple graphical interface, if feasible without making the networking logic unnecessarily complicated
- Maven only if genuinely useful; otherwise provide a simple project that can be compiled using `javac`
- Wireshark for observing UDP packets
- JUnit for automated tests if using Maven

Do not use Python for the main implementation. Do not use TCP to transport the actual project data. TCP may be used only as an optional benchmark in a later extension.

The protocol's application data must travel through UDP sockets.

## 2. Required architecture

Implement separate client and server programs. They must run as independent processes and communicate through UDP.

At minimum, create the following files:

```text
ReliableUDP/
├── src/
│   └── main/
│       └── java/
│           └── reliableudp/
│               ├── Client.java
│               ├── Server.java
│               ├── protocol/
│               │   ├── Packet.java
│               │   ├── PacketType.java
│               │   ├── PacketCodec.java
│               │   └── ProtocolConfig.java
│               ├── transport/
│               │   ├── ReliableSender.java
│               │   ├── ReliableReceiver.java
│               │   ├── SlidingWindow.java
│               │   └── TransferSession.java
│               ├── simulation/
│               │   └── NetworkConditions.java
│               ├── metrics/
│               │   ├── TransferMetrics.java
│               │   └── MetricsLogger.java
│               └── util/
│                   ├── FileChecksum.java
│                   └── Logger.java
├── tests/
├── downloads/
├── logs/
├── README.md
└── run instructions
```

You may improve this structure if needed, but keep the client, server, protocol, transport, metrics, and tests logically separated.

Do not create empty placeholder classes. Every included class must have a clear purpose and working implementation.

## 3. Protocol design

Design a custom packet format and document every field, size, and purpose.

Support at least these packet types:

- `SYN`: initiate a transfer session
- `SYN_ACK`: acknowledge session initiation
- `DATA`: carry a chunk of file data
- `ACK`: acknowledge received data
- `FIN`: indicate the sender has completed transmission
- `FIN_ACK`: acknowledge completion
- `ERROR`: report protocol or transfer errors

Use a clearly documented header containing suitable fields such as:

- Protocol version
- Packet type
- Session identifier
- Sequence number
- Acknowledgement number
- Payload length
- Advertised receive window
- Checksum or integrity field

Use an explicit binary encoding and decoding implementation. Define byte order, field sizes, validation rules, and maximum packet size. Do not serialize arbitrary Java objects over the network.

The packet format must be consistent between the client and server.

Reject malformed packets and packets with invalid lengths or unsupported protocol versions.

## 4. Reliability mechanisms

Implement the following features.

### A. Connection/session establishment

Create a lightweight handshake using UDP packets. A successful handshake must establish a session identifier and initialize transfer state.

### B. Sequence numbers

Every DATA packet must carry a sequence number. The receiver must identify missing packets, out-of-order packets, and duplicates.

### C. Acknowledgements

The receiver must acknowledge successfully received packets. Clearly document whether ACKs are cumulative, selective, or both.

### D. Timeout and retransmission

Maintain a timer for unacknowledged packets. Retransmit packets after a configurable timeout. Limit retransmissions and terminate with a clear error if a peer becomes unreachable.

### E. Sliding window

Allow multiple packets to be in flight instead of sending only one packet and waiting for its ACK. Implement a configurable window size and enforce the receiver's advertised capacity.

Use a documented algorithm. Selective Repeat is preferred if it can be implemented correctly; otherwise implement Go-Back-N correctly and explain the choice.

Do not claim to implement Selective Repeat if the implementation actually retransmits all outstanding packets.

### F. Receiver reassembly

Buffer out-of-order packets, handle duplicates, and write file chunks in the correct order. Do not mark the transfer successful while required data is missing.

### G. Transfer termination

Implement an orderly transfer-completion handshake. Ensure that a lost final ACK does not immediately cause the server to discard the session and leave the client reporting a false failure.

### H. File integrity

Calculate a SHA-256 checksum of the source file and the reconstructed file. Compare them after transfer and report whether they match.

SHA-256 is for end-to-end integrity verification. Do not describe it as encryption or authentication.

## 5. Network-condition simulation

Create a configurable network impairment layer that can simulate:

- Random packet loss
- Artificial delay
- Optional random delay variation
- Optional packet corruption

Allow different values for outgoing client and server traffic.

Provide a reproducible random seed for experiments.

Important: simulated loss and delay must be applied to actual protocol packets in the application, not merely displayed in the UI. Clearly distinguish simulated packet loss from real network packet loss.

If packet corruption is implemented, validate packet integrity before accepting a packet. Explain the limitation of the selected checksum mechanism.

Keep the impairment simulator modular so it can be disabled completely.

## 6. Client functionality

The client must:

- Accept the server IP address and UDP port
- Establish a transfer session
- Select a file for transfer
- Configure chunk size, window size, timeout, and impairment settings
- Send file data through the custom UDP protocol
- Display transfer progress
- Display sent packets, acknowledgements, timeouts, retransmissions, and errors
- Display transfer completion status
- Compare source and receiver SHA-256 checksums where the receiver result is available
- Export transfer statistics

Provide a functional command-line interface first. A GUI may be added afterward, but the CLI must remain usable independently.

## 7. Server functionality

The server must:

- Listen on a configurable UDP port
- Accept transfer-session requests
- Receive DATA packets and send acknowledgements
- Handle duplicate and out-of-order packets correctly
- Reconstruct files in a designated `downloads/` directory
- Prevent path traversal and unsafe file paths
- Validate received packet lengths and session identifiers
- Compute the reconstructed file's SHA-256 checksum
- Send final transfer status to the client
- Log transfer details and protocol events
- Handle invalid packets and disconnects without crashing

Do not permit arbitrary paths supplied by the client to overwrite server files.

## 8. Performance metrics

Collect actual measurements rather than hardcoded or fabricated values.

Record:

- File size in bytes
- Total transfer duration
- Application-level throughput in bytes per second and Mbps
- Number of DATA packets sent
- Number of ACK packets received
- Number of retransmissions
- Number of timeouts
- Number of simulated packets dropped
- Number of detected corrupt packets, if corruption simulation is enabled
- Estimated round-trip time (RTT)
- Goodput: useful file payload bytes successfully delivered per unit time
- Source and destination SHA-256 match status

Define each metric in the README. Clearly distinguish estimated RTT, simulated loss, actual packet counts, and application-level throughput.

Export each experiment to CSV with appropriate column headings and a timestamp.

Do not invent experimental results.

## 9. Testing requirements

Create tests for:

1. Transfer with no simulated loss
2. Transfer with 5% simulated loss
3. Transfer with 10% simulated loss
4. Transfer with artificial delay
5. Transfer with loss and delay combined
6. Duplicate DATA packets
7. Out-of-order DATA packets
8. Invalid or truncated packets
9. File-integrity verification
10. Empty files and small files
11. A file large enough to require many windows
12. Server unavailable or unresponsive
13. Maximum retransmission limit reached
14. Invalid session identifiers
15. Lost final acknowledgements

For every successful transfer, verify the destination file's SHA-256 checksum against the source.

Use deterministic seeds where possible so tests can be repeated.

Test the actual sender and receiver implementation, not just isolated mathematical functions. Where practical, use automated integration tests with both programs running locally on separate UDP ports.

## 10. Performance experiment

Provide a repeatable experiment runner that transfers the same file under several network conditions.

Example configurations:

- Baseline: 0% loss, 0 ms artificial delay
- Low loss: 5% loss, 10 ms artificial delay
- Moderate loss: 10% loss, 25 ms artificial delay
- High delay: 0% loss, 100 ms artificial delay
- Combined impairment: 10% loss, 100 ms artificial delay

Use these as test configurations, not as claimed results.

Generate CSV output and a concise summary comparing transfer duration, goodput, retransmissions, RTT, and integrity status.

Explain expected trends, but derive conclusions from actual measurements.

## 11. Wireshark verification

Document how to:

- Run the client and server locally
- Capture traffic on the loopback interface
- Filter traffic by the chosen UDP port
- Identify session establishment, DATA, ACK, retransmission, and termination packets
- Verify that the application uses UDP

Include example display filters and explain that Wireshark will generally show custom protocol messages as UDP payloads unless a dissector is created.

Do not claim that Wireshark automatically understands the custom protocol.

## 12. Documentation and deliverables

Generate:

1. Complete compilable source code
2. A README with prerequisites and setup
3. Exact commands to compile and run the server
4. Exact commands to compile and run the client
5. A documented packet-format specification
6. An architecture diagram using Mermaid
7. A protocol state diagram
8. A description of the reliability algorithm
9. A testing procedure and results template
10. Sample CSV structure
11. Wireshark setup instructions
12. Known limitations and possible extensions
13. A five-minute project demonstration script
14. Viva questions with concise answers
15. Clear attribution of the purpose of every major source file

Include precise commands for Windows and Linux/macOS wherever practical.

## 13. Implementation process

Build the project incrementally in this order:

**Phase 1:** Define the architecture, protocol header, packet types, and message format.

**Phase 2:** Implement packet encoding/decoding and unit tests.

**Phase 3:** Implement basic UDP client/server communication.

**Phase 4:** Add sequence numbers and acknowledgements.

**Phase 5:** Add timeout, retransmission, and duplicate handling.

**Phase 6:** Add sliding-window transmission and receiver reassembly.

**Phase 7:** Add safe file transfer and SHA-256 verification.

**Phase 8:** Add impairment simulation and metrics collection.

**Phase 9:** Add integration tests, experiment runner, and CSV export.

**Phase 10:** Complete documentation and Wireshark demonstration instructions.

Start by producing the architecture, protocol specification, and implementation plan. Then implement the project in small, testable stages. If the environment supports file creation, create the actual project files. Otherwise, provide complete source files in manageable batches with exact filenames.

After each phase, explain what was implemented and provide commands to compile and test it. Fix compilation errors and failing tests before moving on.

Do not omit important code using phrases such as "implement this yourself", "same as above", or "rest of code omitted". Do not claim a feature works unless it is implemented and tested.

Prioritize correctness, reliability, clarity, and a project that a student can understand and explain during an individual viva.

## 14. How to run the project

The final project must provide two independent entry points:

### Server

`Server.java` listens on a configurable UDP port and receives files.

Illustrative command:

```bash
java reliableudp.Server 5000
```

### Client

`Client.java` connects to the server and sends a file using the custom protocol.

Illustrative command:

```bash
java reliableudp.Client localhost 5000 sample.txt
```

These commands are examples. Make the final commands match the actual package structure and build configuration.

Initially, the client and server should be runnable in separate terminals on the same computer. The project should also support running on two computers on the same LAN, subject to firewall and network configuration.

## 15. Final development priorities

- Make the command-line version work first. A GUI is optional; a correct transport protocol is the core project.
- Do not fake packet loss or metrics. The simulator must actually drop or delay packets, and CSV files must contain measured results.
- Understand the protocol. Explain sequence numbers, ACKs, retransmissions, sliding windows, and integrity verification as each stage is implemented.
- Treat the protocol as an educational application-layer reliability mechanism over UDP, not a replacement for TCP's full congestion-control and reliability machinery.

**Recommended milestone:** Complete Phases 1–7 first. Once client-to-server file transfer works reliably, add impairment simulation, performance measurements, and an optional GUI. This establishes a working project early rather than leaving a large, unfinished codebase.
