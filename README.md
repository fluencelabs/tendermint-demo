# Tendermint Verifiable Computations and Storage Demo

This demo application shows how verifiable computations might be processed by a distributed cluster of nodes. It comes with a set of hardcoded operations that can be invoked by the client. Each requested operation is computed by every (ignoring failures or Byzantine cases) cluster node, and if any node disagrees with the computation outcome it can submit a dispute to an external Judge.

Results of each computation are stored on the cluster nodes and can be later on retrieved by the client. The storage of the results is secured with Merkle proofs, so malicious nodes can't substitute them with bogus data.

Because every computation is verified by the cluster nodes and computation outcomes are verified using Merkle proofs, the client normally doesn't have to interact with the entire cluster. Moreover, the client can interact with as little as a single node – this won't change safety properties. However, liveness might be compromised – for example, if the node the client is interacting with is silently dropping incoming requests.

<p align="center">
<img src="images/cluster_nodes.png" alt="Cluster" width="722px"/>
</p>

## Table of contents
* [Motivation](#motivation)
* [Overview](#overview)	
	* [State machine](#state-machine)
	* [Computations correctness](#computations-correctness)
	* [Tendermint](#tendermint)
	* [Operations](#operations)
* [Installation and run](#installation-and-run)
* [Command-line interface](#command-line-interface)
	* [Effectful operations (`put`)](#effectful-operations-put)
	* [Querying operations (`get, ls`)](#querying-operations-get-ls)
	* [Compute operations (`run`)](#compute-operations-run)
	* [Verbose mode and proofs](#verbose-mode-and-proofs)
* [Implementation details](#implementation-details)
	* [Operations processing](#operations-processing)
	* [Few notes on transactions processing](#few-notes-on-transactions-processing)
	* [Few notes on ABCI queries processing](#few-notes-on-abci-queries-processing)
	* [Client implementation details](#client-implementation-details)
	* [Transactions and Merkle hashes](#transactions-and-merkle-hashes)
* [Problematic situations review ](#problematic-situations-review)

## Motivation
The application is a proof-of-concept of a decentralized system with the following properties:
* Support of arbitrary deterministic operations: simple reads or writes as well as complex computations.
* Total safety of operations when more than 2/3 of the cluster is non-Byzantine.
* Ability to dispute incorrect computations.
* High availability: tolerance to simultaneous failures or Byzantine actions of some subset of nodes.
* High throughput (up to 1000 transactions per second) and low latency (&#126;1-2 seconds) of operations.
* Reasonable blockchain finality time (few seconds).

## Overview
There are following actors in the application network:

* Cluster **nodes** which carry certain *state* and can run computations over it following requests sent by the client. All nodes normally have an identical state – the only exception is when some node is faulty, acting maliciously or has network issues. Computations can be *effectful* – which means they can change the state.

  When the cluster has reached *consensus* on the computation outcome, the new state can be queried by the client – any up-to-date node can serve it. Consensus requires a 2/3 majority of the nodes to agree to avoid Byzantine nodes sneaking in incorrect results.

* The **client** which can issue simple requests like `put` or `get` to update the cluster state as well as run complex computations. In this demo application an available computations set is limited to operations like `sum` or `factorial` but in principle can be extended by a developer.

  The client is able to check whether the cluster has reached consensus over computation outcomes by checking cluster nodes signatures and validate responses to the queries using Merkle proofs. This means that unless the cluster is taken over by Byzantine nodes the client can be certain about results provided and in general about the cluster state.

* The **Judge** which is in charge of resolving disputes over computations outcomes. Different nodes in the cluster might have different opinions on how their state should change when the computation is performed. Unless there is an unanimous consensus, any disagreeing node can escalate to the **Judge** for the final determination and penalization of uncomplying nodes.

  The **Judge** is not represented by a single machine – actually, it can be as large as Ethereum blockchain to have the desired trustworthiness properties. For this showcase, however, it is imitated as a single process stub which can only notify the user if there is any disagreement.

Two major logical parts can be marked out in the demo application. One is a BFT consensus engine with a replicated transaction log which is provided by the Tendermint platform (**Tendermint Core**). Another is a state machine with domain-specific state transitions induced by transactions. Each node in the cluster runs an Tendermint Core instance and a state machine instance, and Tendermint connects nodes together. We will discuss both parts in more details below.

<p align="center">
<img src="images/architecture.png" alt="Architecture" width="834px"/>
</p>

Demo application operates under the following set of assumptions:
* the set of nodes in the cluster is immutable
* public keys of cluster nodes are known to all network participants
* the Judge is trustworthy
* honest nodes can communicate with the client and the Judge

### State machine

Each node carries a state which is updated using transactions furnished through the consensus engine. Assuming that more than 2/3 of the cluster nodes are honest, the BFT consensus engine guarantees *correctness* of state transitions. In other words, unless 1/3 or more of the cluster nodes are Byzantine there is no way the cluster will allow an incorrect transition. 

If every transition made since the genesis was correct, we can expect that the state itself is correct too. Results obtained by querying such a state should be correct as well (assuming a state is a verifiable data structure). However, if at any moment in time there was an incorrect transition, all subsequent states can potentially be incorrect even if all later transitions were correct.

<p align="center">
<img src="images/state_machine.png" alt="State machine" width="702px"/>
</p>

In this demo application the state is implemented as an hierarchical key-value tree which is combined with a Merkle tree. This allows a client that has obtained a correct Merkle root from a trusted location to query a single, potentially malicious, cluster node and validate results using Merkle proofs.

Such trusted location is provided by the Tendermint consensus engine. Cluster nodes reach consensus not only over the canonical order of transactions, but also over the Merkle root of the state – `app_hash` in Tendermint terminology. The client can obtain such Merkle root from any node in the cluster, verify cluster nodes signatures and check that more than 2/3 of the nodes have accepted the Merkle root change – i.e. that consensus was reached.

<p align="center">
<img src="images/hierarchical_tree_basic.png" alt="Hierarchical tree" width="691px"/>
</p>

### Computations correctness

It's possible to notice that a Byzantine node has voted for the two blocks with the same height and penalize it. The fact that a node signed these two blocks is an evidence _per se_. However, if two nodes disagree on how the state should change when transactions are applied, it's dangerous to let the cluster to resolve this for reasonable cluster sizes.

Let's imagine we have a case that the node `A` says the new state should be S<sub>a</sub> and node `B` – S<sub>b</sub>. We could ask nodes in the cluster to vote for one of the states, and if S<sub>a</sub> got more than 2/3 of the votes against it, penalize the node `A`.

Now, let's assume that `n` nodes in the cluster were independently sampled from a large enough pool of the nodes containing a fraction of `q` Byzantine nodes. In this case the number of Byzantine nodes in the cluster (denoted by `X`) approximately follows a Binomial distribution `B(n, q)`. The probability of the cluster having more than 2/3 Byzantine nodes is `Pr(X >= ceil(2/3 * n))` which for 7 cluster nodes and 20% of Byzantine nodes in the network pool is `~0.5%`. 

This means, with a pretty high probability we might have a cluster penalizing a node that have computed the state update correctly. If we want to keep the cluster size reasonably low we can't let the cluster make decisions of that matter. Instead, we can allow any node in the cluster to escalate to the external trusted Judge if it disagrees with state transitions made by any other node. In this case, it's not possible to penalize an honest node.

It's important to note that in the case of 2/3+ Byzantine nodes the cluster might provide an incorrect result to the unsuspecting client. The rest of the nodes which are honest might escalate to the Judge if they are not excluded from the communication by the Byzantine nodes (actually, this situation requires further investigation). However, in this case the dispute might get resolved significant time later. To compensate, the Judge can penalize malicious nodes by forfeiting their security deposits for the benefit of the client. However, even in this case a client can't be a mission critical application where no amount of compensation would offset the damage made.

### Tendermint

[Tendermint](https://github.com/tendermint/tendermint) platform provides a Byzantine-resistant consensus engine (**Tendermint Core**) which roughly consists of the following parts:
* distributed transaction cache (**mempool**)
* blockchain to store transactions
* Byzantine-resistant **сonsensus** module (to reach the agreement on the order of transactions)
* peer-to-peer layer to communicate with other nodes
* **RPC endpoint** to serve client requests
* **query processor** for making requests to the state

To execute domain-specific logic the application state machine implements Tendermint's [ABCI interface](http://tendermint.readthedocs.io/projects/tools/en/master/abci-spec.html). It is written in `Scala 2.12`, compatible with `Tendermint v0.19.x` and uses the [`jabci`](https://mvnrepository.com/artifact/com.github.jtendermint/jabci) library providing ABCI definitions for JVM languages.

Tendermint orders incoming transactions, passes them to the application and stores them persistently. It also combines transactions into ordered lists – _blocks_. Besides the transaction list, a block also has some [metadata](http://tendermint.readthedocs.io/en/master/specification/block-structure.html) that helps to provide integrity and verifiability guarantees. Basically this metadata consists of two major parts:
* metadata related to the current block
	* `height` – an index of this block in the blockchain
	* block creation time
	* hash of the transaction list in the block
	* hash of the previous block
* metadata related to the previous block
	* `app_hash` – hash of the state machine state that was achieved at the end of the previous block
	* previous block *voting process* information

<p align="center">
<img src="images/blocks.png" alt="Blocks" width="721px"/>
</p>

To create a new block a single node – the block _proposer_ is chosen. The proposer composes the transaction list, prepares the metadata and initiates the [voting process](http://tendermint.readthedocs.io/en/master/introduction.html#consensus-overview). Then other nodes make votes accepting or declining the proposed block. If enough number of votes accepting the block exists (i.e. the _quorum_ was achieved – more than 2/3 of the nodes in the cluster voted positively), the block is considered committed. 

Once this happens, every node's state machine applies newly committed block transactions to the state in the same order those transactions are present in the block. Once all block transactions are applied, the new state machine `app_hash` is memorized by the node and will be used in the next block formation. 

If for some reason a quorum was not reached the proposer is changed and a new _round_ of a block creation for the same `height` as was in the previous attempt is started. This might happen, for example, if there was an invalid proposer, or a proposal containing incorrect hashes, a timed out voting and so on.

Note that the information about the voting process and the `app_hash` achieved during the block processing by the state machine are not stored in the current block. The reason is that this part of metadata is not known to the proposer at time of block creation and becomes available only after successful voting. That's why the `app_hash` and voting information for the current block are stored in the next block. That metadata can be received by external clients only upon the next block commit event.

Grouping transactions together significantly improves performance by reducing the storage and computational overhead per transaction. All metadata including `app_state` and nodes signatures is associated with blocks, not transactions.

It's important to mention that block `height` is a primary identifier of a specific state version. All non-transactional queries to the state machine (e.g. `get` operations) refer to a particular `height`. Also, some blocks might be empty – contain zero transactions. By committing empty blocks, Tendermint maintains the freshness of the state without creating dummy transactions.

Another critical thing to keep in mind is that once the `k` block is committed, only the presence and the order of its transactions is verified, but not the state achieved by their execution. Because the `app_hash` resulted from the block `k` execution is only available when the block `k + 1` is committed, the client has to wait for the next block to trust a result computed by the transaction in the block `k`. To avoid making the client wait if there were no transactions for a while, Tendermint makes the next block (possibly empty) in a short time after the previous block was committed.

### Operations
There are few different operations that can be invoked using the bundled command-line client: 
* `put` operations which request to assign a constant value to the key: `put a/b=10`.
* computational `put` operations which request to assign the function result to the key: `put a/c=factorial(a/b)`
* `get` operations which read the value associated with a specified key: `get a/b`

`put` operations are _effectful_ and are explicitly changing the application state. To process such operation, Tendermint sends a transaction to the state machine which applies this transaction to its state, typically changing the associated value of the specified key. If requested operation is a computational `put`, state machine finds the corresponding function from a set of previously hardcoded ones, executes it and then assigns the result to the target key. 

Correctness of `put` operations can be verified by the presence of a corresponding transaction in a correctly formed block and an undisputed `app_hash` in the next block. Basically, this would mean that a cluster has reached quorum regarding this transaction processing. 

`get` operations do not change the application state and are implemented as Tendermint _ABCI queries_. As the result of such query the state machine returns the value associated with the requested key. Correctness of `get` operations can be verified by matching the Merkle proof of the returned result with the `app_hash` confirmed by consensus.

## Installation and run
To run the application, the node machine needs Scala 2.12 with `sbt`, [Tendermint](http://tendermint.readthedocs.io/en/master/install.html) binary and  GNU `screen` in the PATH.  
To execute operations the client machine needs Python 2.7 with `sha3` package installed.

This demo contains scripts that automate running a cluster of 4 nodes (the smallest BFT ensemble possible) on the local machine. To prepare configuration files, run `tools/local-cluster-init.sh`. This will create a directory `$HOME/.tendermint/cluster4` containing Tendermint configuration and data files.

To start the cluster, run `tools/local-cluster-start.sh` which starts 9 `screen` instances: `app[1-4]` – application backends, `tm[1-4]` – corresponding Tendermint instances and `judge` – Judge stub. Cluster initialization might take few seconds.

Other scripts allow to temporarily stop (`tools/local-cluster-stop.sh`), delete (`tools/local-cluster-delete.sh`) and reinitialize & restart (`tools/local-cluster-reset.sh`) the cluster.

## Command-line interface
Examples below use `localhost:46157` to connect to the 1st local "node". To access other nodes it's possible to use other endpoints (`46257`, `46357`, `46457`). Assuming Byzantine nodes don't silently drop incoming requests, all endpoints behave the same way. To deal with such nodes client could have been sending the same request to multiple nodes at once, but this is not implemented yet.

### Effectful operations (`put`)
To order the cluster to assign a value to the target key, issue:
```bash
> python cli/query.py localhost:46157 put a/b=10
```

To order the cluster to compute a function result and assign it to the target key, run:
```bash
> python cli/query.py localhost:46157 put "a/c=factorial(a/b)"
```

Note that `put` operations do not return any result – they merely instruct Tendermint to add a corresponding transaction to the mempool. An actual transaction execution and state update might happen a bit later, when a new block is formed, accepted by consensus and processed by state machines. It's validation happens even later when the consensus is reached on the next block.

There are few other operations bundled with the demo applicaton:

```bash
# a trick to copy a value from one key to the target key
> python cli/query.py localhost:46157 put "a/d=copy(a/b)"

# increment the value in-place and associate it's old version with the target key
> python cli/query.py localhost:46157 put "a/e=increment(a/d)"

# compute the sum and assign it to the target key
> python cli/query.py localhost:46157 put "a/f=sum(a/d,a/e)"

# compute a hierachical sum of values associated with the key and it's descendants
> python cli/query.py localhost:46157 put "c/a_sum=hiersum(a)"
```

### Querying operations (`get`, `ls`)
To read the value associated with the key, run:
```bash
> python cli/query.py localhost:46157 get a/b
10

> python cli/query.py localhost:46157 get a/c
3628800

> python cli/query.py localhost:46157 get a/d
11

> python cli/query.py localhost:46157 get a/e
10

> python cli/query.py localhost:46157 get a/f
21

> python cli/query.py localhost:46157 get c/a_sum
3628852
```

To list immediate children for the key, issue:
```bash
> python cli/query.py localhost:46157 ls a
e f b c d
```

### Verbose mode and proofs
Verbose mode allows to obtain a little bit more information on how the Tendermint blockchain structure looks like and how the client performs verifications.

**Let's start with `put` operation:**
```bash
> python cli/query.py localhost:46157 put -v root/x=5
height:    7

# wait for few seconds

> python cli/query.py localhost:46157 put -v "root/x_fact=factorial(root/x)"
height:    9
```
In this example, `height` corresponds to the height of the block in which the `put` transaction eventually got included. Now, we can checkout blockchain contents using a `parse_chain.py` script:
```bash
> python cli/parse_chain.py localhost:46157
height                 block time     txs acc.txs app_hash                            tx1
...
    7: 2018-06-26 14:35:02.333653       1       3 0xA981CC                       root/x=5
    8: 2018-06-26 14:35:03.339486       0       3 0x214F37
    9: 2018-06-26 14:35:36.698340       1       4 0x214F37  root/x_fact=factorial(root/x)
   10: 2018-06-26 14:35:37.706811       0       4 0x153A5D
```
This script shows latest blocks in the blockchain with a short summary on their transactions. For example, it's possible to see that provided transaction `root/x=5` was indeed included in the block with height `7`. This means that Tendermint majority (more than 2/3 of the cluster nodes) agreed on including this transaction in this block, and that was certified by their signatures.

It's also possible to see that `app_hash` is modified only in the block following the block with transactions. You could note that block 8 (empty) and block 9 (containing `root/x_fact=factorial(root/x)`) application hashes are the same. However, block 10 has the application hash changed which corresponds to the changes that transactions from the previous block introduced into the application state.

**Now let's pull results back using `get` operation:**
```bash
> python cli/query.py localhost:46157 get -v root/x
height:    9
app_hash:  0x153A5D
5

> python cli/query.py localhost:46157 get -v root/x_fact
height:    9
app_hash:  0x153A5D
120
```
Here we can see that both (correct!) results correspond to the latest `app_hash` – `0x153A5D`.

## Implementation details

### Operations processing
To perform operations, a client normally connects to a single node of the cluster.

Querying requests such as `get` are processed entirely by a single node. Single node state machine has all the required data to process queries because the state is fully replicated. The blockchain is fully replicated as well and each block contains signatures of the nodes accepted it, so the client can verify that the `app_hash` return by the node is not a bogus one.

Processing effectful requests such as `put` requires multiple nodes to reach consensus, however the client still sends a transaction to a single node. Tendermint is responsible in spreading this transaction to other nodes and reaching consensus.

### Few notes on transactions processing
A transaction is processed primarily by two Tendermint modules: mempool and consensus.

It appears in mempool once one of Tendermint [RPC](https://tendermint.readthedocs.io/projects/tools/en/master/specification/rpc.html) `broadcast` methods is invoked. After that the mempool invokes the application's `CheckTx` ABCI method. The application might reject the transaction if it's invalid, in which case the transaction is removed from the mempool and the node doesn't need to connect to other nodes. Otherwise Tendermint starts spreading transaction to other nodes.

<p align="center">
<img src="images/mempool.png" alt="Mempool" width="823px"/>
</p>

The transaction remains for some time in mempool until consensus module of the current Tendermint proposer consumes it, includes to the newly created block (possibly together with several other transactions) and initiates a voting process for this block. If the transaction rate is intensive enough or exceeds the node throughput, it is possible that the transaction might wait during few blocks formation before it is eventually consumed by proposer. Note that transaction broadcast and the block proposal are processed independently, so it is totally possible that the block proposer is not the node originally accepted and broadcaster the transaction.

If more than 2/3 of the cluster nodes voted for the proposal in a timely manner, the voting process ends successfully. In this case every Tendermint instance starts synchronizing the block with its local state machine. It consecutively invokes the state machine's ABCI methods: `BeginBlock`, `DeliverTx` (for each transaction), `EndBlock` and lastly, `Commit`. The state machine applies transactions in the order they are delivered, calculates the new `app_hash` and returns it to Tendermint Core. At that moment the block processing ends and the block becomes available to the outside world (via the RPC methods like `block` and `blockchain`). Tendermint keeps `app_hash` and the information about a voting process so it can include it into the next block metadata.

<p align="center">
<img src="images/consensus.png" alt="Consensus" width="823px"/>
</p>

### Few notes on ABCI queries processing
ABCI queries carry the target key and the target `height`. They are initially processed by the Tendermint query processor which reroutes them to the state machine.

State machine processes the query by looking up for the target key in a state corresponding to the block with specified `height`. This means that the state machine has to maintain several states (corresponding to different block heights) to be able to serve different queries.

<p align="center">
<img src="images/abci_queries.png" alt="ABCI queries" width="823px"/>
</p>

Note that state machine handles mempool (`CheckTx`), consensus (`DeliverTx`, `Commit`, etc) and queries pipelines concurrently. Because the target `height` is explicitly requested by queries, state machine maintains separate states for those pipelines. This way so serving queries is not affected when transactions are being concurrently delivered to the application.

### Transactions and Merkle hashes
State machine does not recalculate Merkle hashes during `DeliverTx` processing. Instead, the state machine modifies the tree and marks changed paths by clearing Merkle hashes until ABCI `Commit` method is invoked. This allegedly should improve performance when there are several transactions in the block.

<p align="center">
<img src="images/hierarchical_tree_updated.png" alt="Hierarchical tree after DeliverTx()" width="691px"/>
</p>

On `Commit` the State machine recalculates Merkle hash along changed paths only. Finally, the app returns the resulting root Merkle hash to TM Core and this hash is stored as `app_hash` for a corresponding block.

<p align="center">
<img src="images/hierarchical_tree_committed.png" alt="Hierarchical tree after Commit()" width="691px"/>
</p>

Note that described merkelized structure is used just for demo purposes and is not self-balanced. It remains efficient only while transactions keep it relatively balanced. Something like [Patricia tree](https://github.com/ethereum/wiki/wiki/Patricia-Tree) or Merkle B-Tree should be more appropriate to achieve self-balancing.

## Problematic situations review 
There is a number of situations which might potentially go wrong with this demo application. Some of them are already handled by the client, some of them could have been handled but not supported yet, some could be detected but can't be handled without the external intervention. Below we will try to list a few, just keep in mind that list is not comprehensive by any means:

* A node might become unavailable because of the network issues, failing hardware or simply because the malicious node decided to start dropping requests. If this happens when the client is making requests, it can get noticed using timeouts. In this case the client simply retries the request, but now sends it to a different node. For the cluster it also doesn't create too many issues, because even with a fraction of unavailable nodes the cluster is able to reach consensus.

* It's possible that a Byzantine node might silently drop incoming transactions so they will never get propagated over the cluster. The client could monitor the blockchain to see if transaction got finally included in an accepted block, and if it didn't – resend it to other nodes.

* It is also possible that a Byzantine (or simply struggling) node might use a stale state and stale blockchain to serve queries. In this case the client has no way of knowing that the cluster state has advanced. Few different approaches can be used, however – the first is to send a `no-op` transaction and wait for it to appear in the selected node blockchain. Because including a transaction into the blockchain means including and processing all transactions before it, the client will have a guarantee that the state has advanced. Another approach is to query multiple nodes at once about their last block height and compare it to the last block height of the selected node. Finally, the client might just expect some maximum time until the next block is formed and switch to another node if nothing happens.

* When querying Tendermint RPCs on a particular node, it might return an inconsistent information. For example, that might be incorrect block hashes, incorrect vote signatures or signatures that are not matched with known public keys. In this case the client can treat the node as possibly Byzantine and retries request to another node.

* When making queries to a particular node's state, a combination of `result + proof` which is inconsistent with the target `app_hash` might be returned. In this case the client also can't trust this node and should switch to another one.

* The cluster cannot commit the block `k+1` while the сlient waits for it to have state `k` verifiable. Not much can be done here; possible reasons might be too many failed (or Byzantine) nodes to have the quorum for consensus. A subclass of this issue is when a consensus wasn't reached because there was a disagreement on the next `app_hash` among the nodes. In this case at least one node has performed an incorrect state update, and it's probable that a dispute was escalated to the Judge.

* If there are too many Byzantine nodes, consensus over the incorrect state transition might be reached. If there is at least one honest node in the cluster, it might escalate a dispute to the Judge (unless it was excluded from the cluster communication by colluding Byzantine nodes).

