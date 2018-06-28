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

def parse_utc(utctxt):
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

def format_bytes(value):
	if value < 1024:
		return "%.0f B" % value
	elif value < 1024 * 1024:
		return "%.3f KiB" % (value / 1024.0)
	else:
		return "%.3f MiB" % (value / 1024.0 / 1024.0)

def read_json(url):
	response = urllib.urlopen("http://" + url)
	return json.loads(response.read())

def get_sync_info(tmaddress):
	status = read_json(tmaddress + "/status")["result"]
	if "sync_info" in status: # compatibility
		return status["sync_info"]
	else:
		return status

def get_max_height(tmaddress):
	return get_sync_info(tmaddress)["latest_block_height"]
