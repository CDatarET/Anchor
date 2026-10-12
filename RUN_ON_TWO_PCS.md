# How to Run Reliable UDP on Two Different PCs (Same Local Network / WiFi)

This guide explains how to send files from one computer to another over your local WiFi/LAN network.

---

## Before You Start

Both PCs need:
- Java 17+ installed (`java --version` should work)
- The project compiled (see `RUN_INSTRUCTIONS.md` or run `scripts\build.bat` / `./scripts/build.sh`)
- Connected to the **same local network / WiFi**

You will need to know:
- **Server PC's IP address** (the PC that will receive the file)

---

## Step 1: Find the Server PC's IP Address

On the PC that will **receive** the file (Server PC):

### Windows:
```powershell
ipconfig
```
Look for your **Wireless LAN adapter Wi-Fi** or **Ethernet** section and find the `IPv4 Address`:
```text
Wireless LAN adapter Wi-Fi:
   IPv4 Address. . . . . . . . . : <SERVER_IP>    <-- Note down this IP address
```

### Linux / macOS:
```bash
ip addr show
# or
ifconfig
```

Write down this IP address (referred to below as `<SERVER_IP>`).

---

## Step 2: Allow Java Through Firewall (Server PC)

When you first start the server on Windows, a firewall dialog may appear.
**Click "Allow access"** for Private networks.

If you need to configure it manually:
1. Open **Windows Security** → **Firewall & network protection**
2. Click **Allow an app through firewall**
3. Ensure **Java(TM) Platform SE binary** is checked for **Private** networks.

---

## Step 3: Start the Server (Receiving PC)

On the PC that will **receive** the file:

```bash
# Windows
java -cp "out\main" reliableudp.Server 5000 --verbose

# Linux / macOS
java -cp out/main reliableudp.Server 5000 --verbose
```

You should see:
```text
[server] listening on UDP port 5000, saving to .../downloads
```

Leave this terminal open.

---

## Step 4: Send a File (Sending PC / Client)

On the **second PC**, run the client command, replacing `<SERVER_IP>` with the actual IP address of the server PC:

```bash
# Windows
java -cp "out\main" reliableudp.Client <SERVER_IP> 5000 "sample.txt" --verbose

# Linux / macOS
java -cp out/main reliableudp.Client <SERVER_IP> 5000 sample.txt --verbose
```

You can send any text file:
```bash
java -cp "out\main" reliableudp.Client <SERVER_IP> 5000 "path/to/your/file.txt"
```

---

## Step 5: Verify Integrity

Once the transfer completes, the receiver saves the file to:
```text
downloads/
```

Both client and server verify the SHA-256 cryptographic checksum. If the hashes match, the transfer succeeded without bit corruption or missing chunks.

---

## Troubleshooting Connectivity

1. **Test basic reachability with ping:**
   From the client machine:
   ```bash
   ping <SERVER_IP>
   ```
2. **If ping works but UDP transfer hangs:**
   Windows Firewall is likely blocking incoming UDP on port 5000. Add an inbound rule allowing UDP port 5000 or allow `java.exe`.
3. **Check network subnet:**
   Ensure both devices are on the same router subnet (not one on guest WiFi and one on main WiFi).
