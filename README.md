
### Encoder for COFDMTV

Following modes are supported:

Using SPC(64800, 43072):
* Mode 6: 8PSK, 2700 Hz BW and about 10 seconds long
* Mode 7: 8PSK, 2500 Hz BW and about 11 seconds long
* Mode 8: QPSK, 2500 Hz BW and about 16 seconds long
* Mode 9: QPSK, 2250 Hz BW and about 18 seconds long

Using SPC(64512, 43072):
* Mode 10: 8PSK, 3200 Hz BW and about 9 seconds long
* Mode 11: 8PSK, 2400 Hz BW and about 11 seconds long
* Mode 12: QPSK, 2400 Hz BW and about 16 seconds long
* Mode 13: QPSK, 1600 Hz BW and about 24 seconds long

Payload must be smaller or equal to ```5380``` bytes.

Following image formats are supported:

* [JPEG](https://en.wikipedia.org/wiki/JPEG)
* [PNG](https://en.wikipedia.org/wiki/Portable_Network_Graphics)
* [WebP](https://en.wikipedia.org/wiki/WebP)

Width and height are limited to between ```16``` and ```1024```.

