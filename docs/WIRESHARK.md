# Verifying the protocol with Wireshark

Wireshark does **not** know this protocol. It will show every packet as `UDP` with an opaque `Data` payload, unless you
write a dissector (not included; see "Extensions" in the README). You read the protocol by looking at the payload
bytes using the layout in [PROTOCOL.md](PROTOCOL.md). The offsets below were checked against a real capture (tcpdump on
Linux `lo`); Windows/macOS offsets are derived from the link-header size — confirm them in Wireshark's bytes pane.

## 1. Capture

1. Start the server: `java -cp out/main reliableudp.Server 5000`.
2. Start Wireshark and choose the capture interface:
   * **Linux:** `lo`.
   * **macOS:** `lo0`.
   * **Windows:** the Npcap loopback adapter (*"Adapter for loopback traffic capture"* / *"Npcap Loopback Adapter"*). It is installed with Npcap; if it is missing, re-run the Npcap installer with loopback support enabled.
   * For the two-computer LAN demo capture on the real Ethernet/Wi-Fi interface instead.
3. Set a **capture filter** (before starting): `udp port 5000`.
4. Start the capture, *then* run the client:
   `java -cp out/main reliableudp.Client localhost 5000 sample.txt --loss 0.05 --seed 3`
   (a small file such as 20–50 KB keeps the capture readable).

## 2. Display filters

| Goal | Filter |
|---|---|
| Everything of the protocol | `udp.port == 5000` |
| Prove it is UDP, not TCP | `tcp` → must show **no** packets for this exchange. *Statistics → Protocol Hierarchy*: Ethernet/Null → IPv4 → UDP → Data |
| Client → server only | `udp.dstport == 5000` |
| Server → client only | `udp.srcport == 5000` |
| By packet type, recent Wireshark (≥ 3.x with `udp.payload`) | `udp.payload[1] == 1` (SYN), `== 2` (SYN_ACK), `== 3` (DATA), `== 4` (ACK), `== 5` (FIN), `== 6` (FIN_ACK), `== 7` (ERROR) |
| By packet type, using frame offsets | Linux `lo` (14-byte Ethernet header + 20 IP + 8 UDP): `frame[43] == 3`. Windows/macOS loopback (4-byte header): `frame[33] == 3` |
| One specific DATA sequence number (e.g. 2) | `udp.payload[1] == 3 && udp.payload[8:4] == 00:00:00:02` |
| Only small control packets | `udp.length == 30` (an ACK, SYN_ACK or FIN: 22 header bytes + 8 UDP header) |

If your Wireshark rejects `udp.payload`, use *Edit → Preferences → Protocols → UDP → "Try heuristic sub-dissectors"* off
and the `frame[...]` form, or select the *Data* field and read the hex pane.

## 3. Reading the bytes

Payload (= UDP data) layout, byte offsets from the start of the UDP payload:

```
 0      1      2-3     4-7        8-11   12-15  16-17   18-21      22...
version type  window  sessionId   seq    ack    length  CRC-32     payload
```

Example (first packet of a real capture, a SYN for `file.txt`, 6000 bytes, chunk 1000):

```
01 01 00 00 | 6e a1 9c c8 | 00 00 00 00 | 00 00 00 00 | 00 16 | 8d fe 12 25 | 00 00 00 00 00 00 17 70  00 00 03 e8  00 08  "file.txt"
version=1   | session     | seq=0       | ack=0       | len=22| CRC-32      | fileSize=6000            chunk=1000    nameLen=8
type=1(SYN)   =0x6ea19cc8
window=0
```

## 4. What to look for

| Phase | In Wireshark |
|---|---|
| Session establishment | client → server datagram with type `01` (length 22 + 14 + name length), server → client `02` with the same session id and window `00 40` (= 64) |
| Data transfer | many client → server `03` datagrams (UDP length 8 + 22 + chunk size); server → client `04` datagrams of 22 bytes; the ACK's `ack` field (bytes 12–15) rises as data is delivered, its `seq` field (bytes 8–11) echoes the packet that triggered it |
| Sliding window | several `03` packets go out back-to-back before the first `04` returns (up to `--window` packets in flight) |
| Retransmission | a `03` packet whose `seq` (bytes 8–11) already appeared earlier in the capture, sent about one `--timeout` after the first copy. Use the `seq` filter above or *Statistics → Conversations* |
| Out-of-order delivery | ACKs whose `ack` stays constant while `seq` (echo) is higher — the receiver is buffering past a hole |
| Termination | client → server `05` (FIN, `seq` = packet count), server → client `06` (FIN_ACK, UDP length 8 + 22 + 57 = 87). Under FIN_ACK loss you will see a second `05` and a second `06` |

## 5. Simulated loss versus what Wireshark sees

Packets dropped by the **simulator** are discarded inside the program *before* they reach the operating system, so
Wireshark **never sees them**; you only see the effect (a missing sequence number, then a retransmission). To tie a gap to
the simulator run the client with `--verbose`; it logs `[sim] DROP DATA{...seq=N...}` lines. Likewise simulated delay is
applied before the send, so the capture timestamps already include it. Simulated corruption *is* visible: the flipped
packet is on the wire, and the receiver rejects it (its CRC no longer matches) and counts it as invalid.

On loopback, genuine loss is very unlikely; on a real LAN you may additionally see true loss or reordering, which the
protocol handles the same way.
