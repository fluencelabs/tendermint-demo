#!/usr/bin/python
import sys, urllib, json, datetime, time, hashlib, sha3
from common_parse_utils import read_json, get_sync_info, get_max_height

CMD_FAST_PUT = "fastput"
CMD_PUT = "put"
CMD_RUN = "run"
CMD_GET_QUERY = "get"
CMD_LS_QUERY = "ls"
ALL_COMMANDS = {CMD_GET_QUERY, CMD_PUT, CMD_RUN, CMD_FAST_PUT, CMD_LS_QUERY}

def verify_merkle_proof(result, proof, app_hash):
	parts = proof.split(", ")
	parts_len = len(parts)
	for index in range(parts_len, -1, -1):
		low_string = parts[index] if index < parts_len else result
		low_hash = hashlib.sha3_256(low_string).hexdigest()
		high_hashes = parts[index - 1].split(" ") if index > 0 else [app_hash.lower()]
		if not any(low_hash in s for s in high_hashes):
			return False
	return True

def checked_abci_query(tmaddress, height, command, query, tentative_info):
	if get_max_height(tmaddress) < height + 1:
		return (height, None, None, None, False, "Cannot verify tentative '%s'! Height is not verifiable" % (info or ""))

	app_hash = read_json('%s/block?height=%d' % (tmaddress, height + 1))["result"]["block"]["header"]["app_hash"]
	response = read_json('%s/abci_query?height=%d&data="%s:%s"' % (tmaddress, height, command, query))["result"]["response"]
	(result, proof) = (
		response["value"].decode('base64') if "value" in response else None,
		response["proof"].decode('base64') if "proof" in response else None
		)
	if result is None:
		return (height, result, proof, app_hash, False, "Result is empty")
	elif tentative_info is not None and result != tentative_info:
		return (height, result, proof, app_hash, False, "Verified result '%s' doesn't match tentative '%s'!" % (result, info))
	elif proof is None:
		return (height, result, proof, app_hash, False, "No proof")
	elif not verify_merkle_proof(result, proof, app_hash) :
		return (height, result, proof, app_hash, False, "Proof is invalid")
	else:
		return (height, result, proof, app_hash, True, "")

def print_response(attribute, value, always=False):
	need_print = always or "v" in flags
	if need_print:
		print attribute.upper() + ":", (8 - len(attribute)) * " ", value

def print_checked_abci_query(tmaddress, height, command, query, tentative_info):
	(height, result, proof, app_hash, success, message) = checked_abci_query(tmaddress, height, command, query, tentative_info)
	print_response("height", height)
	print_response("app_hash", app_hash or "NOT_READY")
	print_response("proof", (proof or "NO_PROOF").upper())
	print_response("result", result or "EMPTY", True)
	if success:
		print "OK"
	else:
		print_response("bad", message, True)

def latest_provable_height(tmaddress):
	return get_sync_info(tmaddress)["latest_block_height"] - 1

def wait_for_height(tmaddress, height):
	for w in range(0, 5):
		if get_max_height(tmaddress) >= height:
			break
		time.sleep(1)

		

num_args = len(sys.argv)
if num_args < 4 or not sys.argv[2] in ALL_COMMANDS:
	print "usage: python query.py host:port <command> [flags] arg"
	print "<command> is one of:", ", ".join(ALL_COMMANDS)
	sys.exit()

tmaddress = sys.argv[1]
command = sys.argv[2]
flags = "".join(sys.argv[3:(num_args - 1)])
arg = sys.argv[num_args - 1]
if command in {CMD_FAST_PUT, CMD_PUT, CMD_RUN}:
	if command == CMD_RUN:
		query_key = "optarg"
		tx = query_key + "=" + arg
	else:
		tx = arg
		query_key = tx.split("=")[0]
	response = read_json(tmaddress + '/broadcast_tx_commit?tx="' + tx + '"')
	if "error" in response:
		print_response("error", response["error"]["data"], True)
	else:
		height = response["result"]["height"]
		if response["result"].get("deliver_tx", {}).get("code", "0") != "0":
			print_response("height", height)
			print_response("bad", log or "NO_MESSAGE", True)
		else:
			info = response["result"].get("deliver_tx", {}).get("info")
			if command in {CMD_PUT, CMD_RUN} and info is not None:
				wait_for_height(tmaddress, height + 1)
				print_checked_abci_query(tmaddress, height, "get", query_key, info)
			else:
				print_response("height", height)
				print_response("info", info or "EMPTY")
				print "OK"
elif command in {CMD_GET_QUERY, CMD_LS_QUERY}:
	height = latest_provable_height(tmaddress)
	print_checked_abci_query(tmaddress, height, command, arg, None)