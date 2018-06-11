# Fluence cluster typical operation processing

Fluence is distributed computations platform. It contains following components:
* Client proxy (Proxy)
* Node Tendermint (TM) with important modules: Mempool, Consensus and Query
* Node ABCI App (App)

Clients typically interact with Fluence via local Proxy. Basically Proxy provides some API like:
```scala
fun doSomeOperation(req: SomeRequest): SomeResponse
```

## Normal-case operation

### A. How client sees operation processing
From the client point of view it just calls API function with similar signature synchronously (in blocking mode) or call asynchronous API request function (with provided response callback).

### B. How Proxy sees operation processing
Let's observe how operation processing looks like.
1. Proxy gets API call from the client.
2. Proxy decomposes operation into 2 interactions with cluster: transaction submit and response query.
3. Obtains some state key `opTarget` (it is chosen from some pool of such temporary target keys).
4. For transaction submit Proxy:
	* Serializes API call to some string `opTx` like: "opTarget=SomeOperation(reqParam1,reqParam2)". Its binary representation is *transaction* in terms of Tendermint.
	* Queries some TM via RPC call: `http://<node_host>:46678/broadcast_tx_commit?tx=<opTx>`.
	* In case of correct (without error messages and not timed out) TM response it treats `height` from it as `opHeight` and considers transaction committed (but yet not validated) and proceeds to the next step.
5. Proxy check whether `opHeight`-th block contains `opTx` indeed:
	* Queries `http://<node_host>:46678/block?height=<opHeight>`.
	* In case of correct TM response it checks for `opTx` existence in transaction list section of response and checks block signature.
	* Upon leaving this step Proxy is sure that the cluster has already performed the operation, committed it to the state, but it has no information about reaching consensus for the operation result.
6. Proxy waits for `opHeight+1`-th block to ensure cluster consensus for resulting app hash:
	* Waits some small time.
	* Starts periodically querying `http://<node_host>:46678/block?height=<opHeight+1>`.
	* Once getting successful response, it checks block signatures.
	* It also get `app_hash` for response (it corresponds to app hash after `height`-th block).
	* Query loop in this step can be replaced with `NewBlock` subscription via WebSocket RPC.
	* Upon leaving this step Proxy is sure that the cluster has already performed the operation, wrote it to `opTarget` and reached consensus about `opTarget` value.
7. Proxy queries `opTarget` value:
	* It makes RPC call for key-value read with explicit height and claim for proof `http://node_host>:46678/abci_query?height=<opHeight>&prove=true&path=<opTarget>`.
	* It got response containing `value` (interpreted as `opResult`) and `proof`.
	* It checks that `opResult`, `proof` and `app_hash` are consistent with each other.
8. Proxy deserialize `opResult` as SomeResponse and returns it to the client.

### C. How Tendermint sees transaction submit
Let's look how Tendermint on some node (say, N) treats transaction submit (step B4) and makes some post-submit checks (B5, B6).
1. TM gets `broadcast_tx_commit` RPC call with `opTx` binary string from Proxy.
2. Mempool processing:
	* TM's RPC endpoint tranfers the transaction to TM's *Mempool* module.
	* Mempool prepares a callback to provide some RPC response when transaction would be committed or rejected.
	* Mempool invokes local App's `CheckTx` ABCI method. If App returns non-zero code then the transaction is considered rejected, this information is sending to the client via callback and no further action happens.
	* If App returns zero code the transaction gossip begins: the `opTx` starts spreading through other nodes.
	* Also Mempool caches the transaction (in order to not accept repeated broadcasts of `opTx`).
3. Consensus processing:
	* When the current TM proposer (*Consensus* module some node PN, PN is possibly N) is ready to create new block it grabs some amount of the oldest yet not committed transactions from local Mempool. If the transaction rate is intensive enough or even exceed TM/App throughput, it is possible that `opTx` may 'wait' during several block formation before it would be grabbed by Consensus.
	* As soon as `opTx` and other transactions reaches Consensus module, block election starts. Proposer creates block proposal (that describes all transactions in the current block) for current *round*, then other nodes makes votes. In order to reach consensus for the block, election should pass all consensus stages (propose, pre-vote, pre-commit) with the majority of TM votes (more that 2/3 of TM's). If this doesn't work by some reason (votes time out, Byzantive proposer), proposer changed and a new round starts (possibly with another transaction set for the current block).
4. Post-consensus interaction with the local App:
	* When election successfully passed all stages each corrent TM undertands that consensus is reached. Then it invokes App's ABCI methods: `BeginBlock`, `DeliverTx` (for each transaction), `EndBlock`, `Commit`.
	* An information from `opTx`' `DeliverTx` call then sent back to Proxy via callback prepared on Step C2.
	* `app_hash` field from `Commit` call is stored by TM before making the next block.
5. The new block metadata and transaction set now gets associated via height `height` and becomes available via RPC's like `block`, `blockchain`, `status` (including call in Step B5). However the recently obtained from App block app_hash yet not stored in the blockchain (because an App hash for some block stored in the blockchain metadata for the next block).
6. Next block processing:
	* Steps 2-5 repeated for the next, `height+1`-th block. It may take some time, depending on new transactions availability and rate and commit timeout settings.
	* The consensus for `height+1`-th block is only possible if the majority (more that 2/3 of TM's) agree about `height`-th block app has. So `app_hash` information in `height+1`-th block header refers to `app_hash` provided on Step C4 for `height`-th block (which is check on Step B6).

### D. How ABCI App sees transaction submit
Now we dig into details of processing the transaction on App side (on node N).
1. On Step C2 TM asks App via `CheckTx` call. This is lightweight checking that works well if some signification part of transaction might be rejected by App by some reason (for example it is inconsistent after applying some recently committed other transaction). This would safe transaction gossip and need of permanent storing this transaction in the blockchain after commit. On this step App can return non-zero code in case of some check made against the latest committed state fails (`CheckTx` is not intended to make some changes, even against temporary internal structure).
	* In case `CheckTx` invoked once but `opTx` is not grabbed by proposer's Consensus module for the next block, `CheckTx` would be reinvoked for every subsequent block until `opTx` would evnetually grabbed by proposer (because after some block commit, `opTx` might become incorrect).
2. On Step C4 TM invokes App's ABCI `DeliverTx` method.
	* App can reject the transaction (it's OK because lightweight `CheckTx` does not necessary chech any possible failure cases), change nothing and return non-zero code. It this case TM would store the transaction anyway (because the block already formed), but would pass error code and any information from App to Proxy calling `broadcast_tx_commit` RPC.
	* Normally App returns zero return code and apply the tranaction to it's state. It maintains the 'real-time' state that already applied all previous changes not only from previous blocks' transactions but even for all previous transactions in the current block.
3. On Step C4 TM also invokes App's ABCI `Commit` method that signals that block commit is over. App must return the actual state hash (*app hash*) as the result. As said before, this app hash would correspond to `height`-th block and be stored in the `height+1`-th block metadata.

Note that Step D2 and D3 behavior should be purely deterministic which should guarantee in normal (non-Byzantine) case scenario both the same app hash from different nodes and the same app hash from a single node after replaying transactions by TM (for example after node fail). This determinism includes transaction acceptance status (accepted or rejected, transaction application to the real-time state and app hash computation logic.

### E. How Tendermint sees response query
Response query initiated by Proxy on Step B7. Queries are processed by TM's Query module. Processing is very straightforward: it's just proxying the query to the local App.

### F. How ABCI App sees response query
Query processing on the App performed in the following way:
1. App gets `height`, `prove` flag and `path` from the query.
2. The query should be applied to the state exactly corresponded to `height`-th block (this is not 'real-time' consensus state and in general not 'mempool' state).
	* In case App do not store all block states and `height` is too old, it might reject the query.
	* Otherwise it applies query to the corresponding state. Queries might be complex enough but not every query might be proved efficiently that why it's expected that queries are usually look like specific value's read or hierarchical structure scan.
	* In case of read query on Step B7 App just reads `opTarget` value previously written by applying `opTx` and committed in `height`-th block.
3. If proof flag requested (as on Step B7) App also produce Merkle path (or some other provable information) that supply `opTarget` value verification with respect to given `height` and it's app hash (from `height+1` block metadata)
4. The response containing value, Merkle proof and any other information are sent back to the local TM.

## Dispute cases
The next sections describe behavior in case of disagreement between cluster nodes about `height`-s app hash.

### Dispute case 1: honest quorum, some nodes dishonest or not available
TODO

### Dispute case 2: some nodes honest, some not, no quorum
TODO

### Dispute case 2: dishonest quorum, minority of honest nodes
TODO
