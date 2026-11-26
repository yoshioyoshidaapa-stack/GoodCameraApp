import sys, time
import serial
port = sys.argv[1]
combos = [(True, True), (False, False), (True, False), (False, True)]
for baud in (115200, 74880):
    for dtr, rts in combos:
        print("=== baud", baud, "dtr", dtr, "rts", rts, "===")
        s = serial.Serial()
        s.port = port
        s.baudrate = baud
        s.dtr = dtr
        s.rts = rts
        s.timeout = 0.2
        s.open()
        print("Press EN on the board now (4 seconds)")
        end = time.time() + 4
        data = b""
        while time.time() < end:
            data += s.read(200)
        s.write(b"\r\x03\x03")
        time.sleep(0.8)
        data2 = s.read(300)
        s.close()
        print("after EN :", repr(data[-300:]))
        print("after ^C :", repr(data2))
        time.sleep(0.5)
