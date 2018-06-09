# Fluence cluster typical operation processing

Fluence is distributed platform. It contains following components:
* Client proxy (Proxy)
* Node Tendermint (TM)
* Node ABCI App (App)

Clients typically interact with Fluence via local Proxy. Basically Proxy provides some API like:
```scala
fun doSomeOperation(req: SomeRequest): SomeResponse
```

## How client sees operation processing
From the client point of view it just calls API function with similar signature synchronously (in blocking mode) or call asynchronous API request function (with provided response callback).

## How Proxy sees operation processing
Let's review how operation processing looks like.
1. Proxy gets API call from the client.
2. Proxy decomposes operation into 2 interactions with cluster: transaction submit and response query
3. Obtains some state key `opTarget` (it is chosen from some pool of such temporary target keys)
4. For transaction submit Proxy:
	* Serializes API call to some string `opTx` like: "opTarget=SomeOperation(reqParam1,reqParam2)". Its binary representation is *transaction* for TM
	* Queries some TM via RPC call: `http://<node_host>:46678/broadcast_tx_commit?tx=<opTx>`
	* In case of correct (without error messages and not timed out) TM response it treats `height` from it as `opHeight` and considers transaction committed (but yet not validated) and proceeds to the next step
5. Proxy check whether `opHeight`-th block contains `opTx` indeed:
	* Queries `http://<node_host>:46678/block?height=<opHeight>`
	* In case of correct TM response it checks for `opTx` existence in transaction list section of response and checks block signature
6. Proxy waits for `opHeight+1`-th block to ensure cluster consensus for resulting app hash:
	* Waits some small time
	* Starts periodically querying `http://<node_host>:46678/block?height=<opHeight+1>`
	* Once getting successful response, it checks block signatures
	* It also get `app_hash` for response (it corresponds to app hash after `height`-th block)
	* Query loop in this step can be replaced with `NewBlock` subscription via WebSocket RPC
	* Upon leaving this step Proxy is sure that the cluster already performed operation processing, wrote it to `opTarget` and reached consensus about `opTarget` value
7. Proxy queries `opTarget` value
	* It makes RPC call for key-value read with explicit height and claim for proof `http://node_host>:46678/abci_query?height=<opHeight>&prove=true&path=<opTarget>`
	* It got response containing `value` (interpreted as `opResult`) and `proof`
	* It checks that `opResult`, `proof` and `app_hash` are consistent with each other
8. Proxy deserialize `opResult` as SomeResponse and returns it to the client

## How Tendermint sees transaction submit
TODO

## How ABCI App sees transaction submit
TODO

## How Tendermint sees response query
TODO

## How ABCI App sees response query
TODO

