import sys, urllib, json, datetime, time
from parse_common import readjson, getsyncinfo, getmaxheight

CMD_TX = "tx"
CMD_TX_VERIFY = "txverify"
CMD_OP = "op"
CMD_GET_QUERY = "get"
CMD_LS_QUERY = "ls"

def abci_query(tmaddress, height, query):
	response = readjson(tmaddress + '/abci_query?height=' + str(height) + '&data="' + query + '"')["result"]["response"]
	return (
		response["value"].decode('base64') if "value" in response else None,
		response["proof"].decode('base64') if "proof" in response else None
		)

if len(sys.argv) < 3:
	print "usage: python query.py host:port command arg"
	sys.exit()

tmaddress = sys.argv[1]
command = sys.argv[2]
arg = sys.argv[3]
if command in {CMD_TX, CMD_TX_VERIFY, CMD_OP}:
	if command == CMD_OP:
		query_key = "optarg"
		tx = query_key + "=" + arg
	else:
		tx = arg
		query_key = tx.split("=")[0]
	response = readjson(tmaddress + '/broadcast_tx_commit?tx="' + tx + '"')
	if "error" in response:
		print "ERROR :", response["error"]["data"]
	else:
		height = response["result"]["height"]
		if response["result"].get("deliver_tx", {}).get("code", "0") != "0":
			log = response["result"].get("deliver_tx", {}).get("log")
			print "BAD"
			print "HEIGHT:", height
			print "LOG:   ", log or "EMPTY"
		else:
			info = response["result"].get("deliver_tx", {}).get("info")
			print "HEIGHT:", height
			if command in {CMD_TX_VERIFY, CMD_OP} and info is not None:
				for w in range(0, 5):
					if getmaxheight(tmaddress) >= height + 1:
						break
					time.sleep(1)
				if getmaxheight(tmaddress) < height + 1:
					print "BAD   :", "Cannot verify tentative result '%s'!" % (info)
				else:
					(result, proof) = abci_query(tmaddress, height, "get:" + query_key)
					if result == info:
						print "OK"
					else:
						print "BAD   :", "Verified result '%s' doesn't match tentative '%s'!" % (result, info)
					print "RESULT:", result or "EMPTY"
					print "PROOF :", proof or "NO_PROOF"
			else:
				print "INFO:  ", info or "EMPTY"
				print "OK"

elif command in {CMD_GET_QUERY, CMD_LS_QUERY}:
	syncinfo = getsyncinfo(tmaddress)
	height = syncinfo["latest_block_height"]
	apphash = syncinfo["latest_app_hash"]
	print "HEIGHT:", height
	print "HASH  :", apphash
	query_response = abci_query(tmaddress, height, command + ":" + arg)
	print "RESULT:", query_response[0] or "EMPTY"
	print "PROOF :", query_response[1] or "NO_PROOF"
