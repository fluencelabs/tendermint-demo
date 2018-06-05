import sys, urllib, json, datetime, time
from parse_common import parseutc, readjson, getmaxheight

if len(sys.argv) < 2:
	print "usage: python parse_chain.py host:port [minheight]"
	sys.exit()

blocks_fetch = 20 # tendermint can't return more blocks
tmaddress = sys.argv[1]
maxheight = getmaxheight(tmaddress)
minheight = int(sys.argv[2]) if len(sys.argv) > 2 else max(1, maxheight - 49)

lastempty = -1
last_fetched_height = minheight - 1
for height in range(minheight, maxheight + 1):
	if height > last_fetched_height:
		last_fetched_height = min(height + blocks_fetch - 1, maxheight)
		bulk_data = (readjson(tmaddress + "/blockchain?minHeight=%d&maxHeight=%d" % (height, last_fetched_height)))["result"]["block_metas"]

	data = bulk_data[last_fetched_height - height]["header"]
	
	numtxs = data["num_txs"]
	totaltxs = data["total_txs"]
	app_hash = data["app_hash"]

	blocktimetxt = data["time"]
	blocktime = parseutc(blocktimetxt)

	if numtxs > 0 or height == maxheight:
		blockdata = readjson(tmaddress + "/block?height=%d" % height)
		txs = blockdata["result"]["block"]["data"]["txs"]
		txsummary = ""
		if txs:
			for tx in txs[0:5]:
				txstr = tx.decode('base64')
				if len(txstr) > 30:
					txsummary += "%27s... " % txstr[0:27]
				else:
					txsummary += "%30s " % txstr
			if len(txs) > 5:
				txsummary += "..."
		print "%5s: %s %7d %7d %s... %s" % (height, datetime.datetime.fromtimestamp(blocktime), numtxs, totaltxs, app_hash[0:6], txsummary)
	else:
		if lastempty < height - 1:
			print "..."
		lastempty = height