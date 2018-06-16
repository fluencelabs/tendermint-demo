import sys, urllib, json, datetime, time

def uvarint(buf):
	x = long(0)
	s = 0
	for b in buf:
		if b < 0x80:
			return x | long(b) << s
		x |= long(b & 0x7f) << s
		s += 7
	return 0

def parseutc(utctxt):
	#tz conversion may be wrong
	now_timestamp = time.time()
	offset = datetime.datetime.fromtimestamp(now_timestamp) - datetime.datetime.utcfromtimestamp(now_timestamp)
	dt, _, tail = utctxt.partition(".")
	if tail == "":
		dt, _, _ = utctxt.partition("Z")
		tail = "0Z"
	pure = int((datetime.datetime.strptime(dt, '%Y-%m-%dT%H:%M:%S') + offset).strftime("%s"))
	ns = int(tail.rstrip("Z").ljust(9, "0"), 10)
	return pure + ns / 1e9
	
def formatbytes(value):
	if value < 1024:
		return "%.0f B" % value
	elif value < 1024 * 1024:
		return "%.3f KiB" % (value / 1024.0)
	else:
		return "%.3f MiB" % (value / 1024.0 / 1024.0)

def readjson(url):
	response = urllib.urlopen("http://" + url)
	return json.loads(response.read())

def getsyncinfo(tmaddress):
	status = readjson(tmaddress + "/status")["result"]
	if "sync_info" in status: # compatibility
		return status["sync_info"]
	else:
		return status

def getmaxheight(tmaddress):
	return getsyncinfo(tmaddress)["latest_block_height"]

