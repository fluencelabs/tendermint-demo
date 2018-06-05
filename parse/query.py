import sys, urllib, json, datetime, time
from parse_common import readjson, getsyncinfo

CMD_TX = "tx"
CMD_TX_VERIFY = "txverify"
CMD_QUERY = "query"

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
if command in {CMD_TX, CMD_TX_VERIFY}:
	response = readjson(tmaddress + '/broadcast_tx_commit?tx="' + arg + '"')
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
			print "OK"
			print "HEIGHT:", height
			print "INFO:  ", info or "EMPTY"
			if command == CMD_TX_VERIFY and info is not None:
				query_key = arg.split("=")[0]
				query_response = abci_query(tmaddress, height, "get:" + query_key)
				print "VERIFY:", query_response[0] or "EMPTY"
				print "PROOF :", query_response[1] or "NO_PROOF"


elif command == CMD_QUERY:
	syncinfo = getsyncinfo(tmaddress)
	height = syncinfo["latest_block_height"]
	apphash = syncinfo["latest_app_hash"]
	print "HEIGHT:", height
	print "HASH  :", apphash
	query_response = abci_query(tmaddress, height, arg)
	print "RESULT:", query_response[0] or "EMPTY"
	print "PROOF :", query_response[1] or "NO_PROOF"
