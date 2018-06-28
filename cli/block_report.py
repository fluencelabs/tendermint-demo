#
#  Copyright 2018 Fluence Labs Limited
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
#

#!/usr/bin/python
import sys, urllib, json, datetime, time
import matplotlib.pyplot as plt
from common_parse_utils import uvarint, parse_utc, format_bytes, read_json, get_max_height

def get_num_txs(json):
	return json["result"]["block"]["header"]["num_txs"]

if len(sys.argv) < 2:
	print "usage: python parse_block.py host:port [report_name [min_height [max_height]]]"
	sys.exit()

tmaddress = sys.argv[1]
report_name = sys.argv[2] if len(sys.argv) > 2 else ""
if len(sys.argv) > 4:
	max_height = int(sys.argv[4])
else:
	max_height = get_max_height(tmaddress)
	while max_height >= 3 and get_num_txs(read_json(tmaddress + "/block?height=%d" % max_height)) == 0:
		max_height -= 1
if len(sys.argv) > 3:
	min_height = int(sys.argv[3])
else:
	min_height = max_height
	while min_height >= 3 and get_num_txs(read_json(tmaddress + "/block?height=%d" % (min_height - 1))) > 0:
		min_height -= 1

accsize = 0
acclatency = 0
minlatency = 1e20
maxlatency = 0
txcount = 0
blockcount = 0
firsttx = 1e20
lasttx = 0
firstblock = 1e20
lastblock = 0
max_block_size = 0

txstat = []
for height in range(min_height, max_height + 1):
	data = read_json(tmaddress + "/block?height=%d" % height)
	num_txs = get_num_txs(data)

	blocktimetxt = data["result"]["block"]["header"]["time"]
	blocktime = parse_utc(blocktimetxt)

	if num_txs > 0:
		firstblock = min(firstblock, blocktime)
		lastblock = max(lastblock, blocktime)
		blockcount += 1
		max_block_size = max(max_block_size, num_txs)

	print height, num_txs, blocktimetxt
	txs = data["result"]["block"]["data"]["txs"]
	if txs:
		for index, txhex in enumerate(txs):
			txbytes = bytearray.fromhex(txhex)# if re.fullmatch(r"^[0-9a-fA-F]$", txhex) is not None
			key = chr(txbytes[0]) if chr(txbytes[1]) == '=' else "*"
			connindex = uvarint(txbytes[2:8])
			txnumber = uvarint(txbytes[8:16])
			hostnamehash = txhex[32:64]

			txtime = uvarint(txbytes[32:40]) / 1e6
			if txtime < 1e9:
				txtime *= 1e6 # legacy support
			latency = blocktime - txtime

			accsize += len(txbytes)
			acclatency += latency
			minlatency = min(minlatency, latency)
			maxlatency = max(maxlatency, latency)
			txcount += 1
			firsttx = min(firsttx, txtime)
			lasttx = max(lasttx, txtime)

			txtimetxt = datetime.datetime.fromtimestamp(txtime)

			txstat.append((txtime, 1))
			txstat.append((blocktime, -1))
			if index < 5:
				print txtimetxt, latency
				#print key, connindex, txnumber, hostnamehash, txtimetxt, latency

print "Transactions:    ", txcount, "=", format_bytes(accsize)
print "                 ", "%.3f s" % (lasttx - firsttx), "from", datetime.datetime.fromtimestamp(firsttx), "to", datetime.datetime.fromtimestamp(lasttx)
print "Blocks:          ", "%d: from %d to %d" % (blockcount, min_height, max_height)
print "                 ", "%.3f s" % (lastblock - firstblock), "from", datetime.datetime.fromtimestamp(firstblock), "to", datetime.datetime.fromtimestamp(lastblock)
print "Tx send rate:    ", "%.3f tx/s" % (txcount / (lasttx - firsttx)), "=", format_bytes(accsize / (lasttx - firsttx)) + "/s"
print "Tx throughput:   ", "%.3f tx/s" % (txcount / (lastblock - firsttx)), "=", format_bytes(accsize / (lastblock - firsttx)) + "/s"
print "Block throughput:", "%.3f block/s" % (blockcount / (lastblock - firsttx))
print "Avg tx latency:  ", "%.3f s" % (acclatency / txcount)
print "Min tx latency:  ", "%.3f s" % minlatency
print "Max tx latency:  ", "%.3f s" % maxlatency

txstat = sorted(txstat)
cursum = 0
curindex = 0
steps = 1000
stepstat = []
for i in range(steps + 1):
	t = firsttx + (lastblock - firsttx) / steps * i
	while curindex < len(txstat) and txstat[curindex][0] <= t:
		cursum += txstat[curindex][1]
		curindex += 1
	stepstat.append(cursum)
f = plt.figure(figsize=(15, 5))
plt.plot([i * (lastblock - firsttx) / steps for i in range(steps + 1)], stepstat)
long_title = "Duration: %.1f s, Tx size: %s, Tx send rate: %.3f tx/s = %s/s, Tx throughput: %.3f tx/s = %s/s" % \
	(lasttx - firsttx, format_bytes(accsize / txcount), \
	txcount / (lasttx - firsttx), format_bytes(accsize / (lasttx - firsttx)), \
	txcount / (lastblock - firsttx), format_bytes(accsize / (lastblock - firsttx)))
#plt.title(long_title)
plt.title(report_name)
plt.xlabel("seconds from first tx")
plt.ylabel("txs in backlog")

if report_name != "":
	long_filename = "tdmnt-stat-%d-%d-%d-%.1f-%.0f-%.0f.png" % \
		(min_height, max_height, max_block_size, lasttx - firsttx, accsize / txcount, txcount / (lasttx - firsttx))
	#f.savefig(long_filename, bbox_inches='tight')
	f.savefig(report_name + ".png", bbox_inches='tight')
plt.show(block=True)
