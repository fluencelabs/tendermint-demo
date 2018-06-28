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
from common_parse_utils import parse_utc, read_json, get_max_height

if len(sys.argv) < 2:
	print "usage: python parse_chain.py host:port [min_height]"
	sys.exit()

blocks_fetch = 20 # tendermint can't return more blocks
tmaddress = sys.argv[1]
max_height = get_max_height(tmaddress)
min_height = int(sys.argv[2]) if len(sys.argv) > 2 else max(1, max_height - 49)

last_non_empty = -1
last_fetched_height = min_height - 1
print "%6s %26s %7s %7s %8s %30s %30s %30s %30s %30s" % ("height", "block time", "txs", "acc.txs", "app_hash", "tx1", "tx2", "tx3", "tx4", "tx5")
for height in range(min_height, max_height + 1):
	if height > last_fetched_height:
		last_fetched_height = min(height + blocks_fetch - 1, max_height)
		bulk_data = (read_json(tmaddress + "/blockchain?minHeight=%d&maxHeight=%d" % (height, last_fetched_height)))["result"]["block_metas"]

	data = bulk_data[last_fetched_height - height]["header"]

	num_txs = data["num_txs"]
	total_txs = data["total_txs"]
	app_hash = data["app_hash"]

	blocktimetxt = data["time"]
	blocktime = parse_utc(blocktimetxt)

	if num_txs > 0 or height == max_height or height == last_non_empty + 1:
		blockdata = read_json(tmaddress + "/block?height=%d" % height)
		txs = blockdata["result"]["block"]["data"]["txs"]
		txsummary = ""
		if txs:
			last_non_empty = height
			for tx in txs[0:5]:
				txstr = tx.decode('base64')
				if len(txstr) > 30:
					txsummary += "%27s... " % txstr[0:27]
				else:
					txsummary += "%30s " % txstr
			if len(txs) > 5:
				txsummary += "..."
		app_hash_to_show = "0x" + app_hash[0:6] if app_hash != "" else "--------"
		print "%5s: %s %7d %7d" % (height, datetime.datetime.fromtimestamp(blocktime), num_txs, total_txs), app_hash_to_show, txsummary
	else:
		if height == last_non_empty + 2:
			print "..."
