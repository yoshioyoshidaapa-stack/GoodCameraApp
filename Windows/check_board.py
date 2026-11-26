import sys, time
import serial  # pyserial (installed together with mpremote)
port = sys.argv[1]
print("Opening", port, "without resetting the board ...")
s = serial.Serial()
s.port = port
s.baudrate = 115200
s.dtr = False
s.rts = False
s.timeout = 0.2
s.open()
print("Listening 4 seconds. Press the EN button on the board NOW.")
end = time.time() + 4
data = b""
while time.time() < end:
    data += s.read(200)
print("--- received (raw) ---")
print(repr(data[-600:]))
print("--- sending Ctrl-C ---")
s.write(b"\r\x03\x03")
time.sleep(1)
print(repr(s.read(400)))
s.close()
