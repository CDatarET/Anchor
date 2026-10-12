# How to Run the Reliable UDP Project

## Prerequisites

- Java 17+ installed (`java --version` and `javac --version` should both work)
- Two terminal windows (Command Prompt, PowerShell, or Bash)

---

## Step 1: Compile (run once)

Open a terminal, `cd` into the project root directory, and compile:

### Windows (PowerShell):
```powershell
if (Test-Path "out\main") { Remove-Item -Recurse -Force "out\main" }
New-Item -ItemType Directory -Force "out\main" | Out-Null
$files = Get-ChildItem -Recurse -Filter "*.java" -Path "src\main\java" | ForEach-Object { "`"$($_.FullName)`"" }
Invoke-Expression ("javac -d `"out\main`" " + ($files -join " "))
```

### Windows (Command Prompt):
```cmd
scripts\build.bat
```

### Linux / macOS:
```bash
./scripts/build.sh
```

You should see no errors. The compiled classes go into `out\main`.

---

## Step 2: Start the Server (Terminal 1)

In your first terminal:

```bash
# Windows
java -cp "out\main" reliableudp.Server 5000

# Linux / macOS
java -cp out/main reliableudp.Server 5000
```

You should see:
```text
[server] listening on UDP port 5000, saving to ...\downloads
```

Leave this terminal running. The server is now waiting for incoming transfers.

### Server options (optional):
```text
--dir <path>       where to save received files (default: downloads)
--window <n>       receive window size in packets (default: 64)
--verbose          show detailed log for every packet
--loss <0..1>      simulate dropping ACKs (e.g. 0.05 = 5%)
--delay <ms>       simulate network delay on server replies
```

---

## Step 3: Send a File (Terminal 2)

Open a **second** terminal in the same directory:

```bash
# Windows
java -cp "out\main" reliableudp.Client localhost 5000 "sample.txt"

# Linux / macOS
java -cp out/main reliableudp.Client localhost 5000 sample.txt
```

*(If transferring across two machines on a network, replace `localhost` with `<server-ip>`, e.g., `java -cp "out\main" reliableudp.Client <server-ip> 5000 "sample.txt"`)*

### Examples:
```powershell
# Send the included sample text file with detailed logs
java -cp "out\main" reliableudp.Client localhost 5000 sample.txt --verbose

# Send any text file
java -cp "out\main" reliableudp.Client localhost 5000 "path\to\your\file.txt"

# Launch interactive prompt (prompts for server IP and file path)
java -cp "out\main" reliableudp.Client localhost 5000
```

### Client options (optional):
```text
--chunk <bytes>    payload per packet (default: 1024)
--window <n>       sender window size (default: 16)
--timeout <ms>     retransmit timeout (default: 200)
--retries <n>      max retries per packet (default: 10)
--loss <0..1>      simulate packet drop probability (e.g. 0.10 = 10%)
--delay <ms>       simulate network delay in milliseconds
--verbose          show every packet sent/received
--no-csv           don't write stats to CSV
```

---

## Step 4: Check the Results

After transfer completes, you will see the final transfer summary:

```text
Result            : SUCCESS
File              : sample.txt (234 bytes)
Throughput        : ~3.2 Mbps
Retransmissions   : 0
SHA-256 source    : 083b2cf7c7...
SHA-256 received  : 083b2cf7c7...
Integrity         : MATCH
```

- Received files are saved in: `./downloads/`
- Transfer statistics are logged in: `./logs/transfers.csv`

---

## Fun Experiments & Loss Simulation

### 1. Transfer with 10% packet loss (simulating lossy WiFi)
```powershell
# Terminal 1 (Server)
java -cp "out\main" reliableudp.Server 5000 --verbose

# Terminal 2 (Client with 10% loss)
java -cp "out\main" reliableudp.Client localhost 5000 sample.txt --loss 0.10 --verbose
```

### 2. Transfer with simulated latency (50ms delay each way)
```powershell
# Terminal 1 (Server with 50ms delay on ACKs)
java -cp "out\main" reliableudp.Server 5000 --delay 50

# Terminal 2 (Client with 50ms delay on DATA)
java -cp "out\main" reliableudp.Client localhost 5000 sample.txt --delay 50
```

### 3. Stress test (5% loss + 25ms delay)
```powershell
# Terminal 1
java -cp "out\main" reliableudp.Server 5000 --loss 0.05 --delay 25 --verbose

# Terminal 2
java -cp "out\main" reliableudp.Client localhost 5000 sample.txt --loss 0.05 --delay 25 --verbose
```

---

## Quick Reference

| Action | Command |
|---|---|
| Build | `scripts\build.bat` (Win) or `./scripts/build.sh` (Unix) |
| Start Server | `java -cp "out\main" reliableudp.Server 5000` |
| Send File | `java -cp "out\main" reliableudp.Client <server-ip> 5000 sample.txt` |
| Received Files | `./downloads/` |
| Metrics Log | `./logs/transfers.csv` |
