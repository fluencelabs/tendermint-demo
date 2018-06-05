# Tendermint Demo ABCI KVStore on Scala
This is demo application implementing Tendermint ABCI interface. It models in-memory key-value string storage. Key here are hierarchical, `/`-separated. This key hierarchy is *merkelized*, so every node stores Merkle hash of its associated value (if present) and its children.
The application is compatible with `Tendermint v0.19.x` and uses `com.github.jtendermint.jabci` for Java ABCI definitions.

## Installation and running
For single-node run just launch the application:
```bash
sbt run
```
And launch Tendermint:
```bash
# uncomment line below to initialize Tendermint
#tendermint init

# uncomment line below to clear all Tendermint data
#tendermint unsafe_reset_all

tendermint node --consensus.create_empty_blocks=false
```

In case Tendermint launched first, it would periodically try to connect the app until the app started. 

## Changing and observing the application state: transactions and queries
Tendermint offers two main ways of interaction with the app: transactions and queries.

Transactions are treated by Tendermint just like arrays of bytes and stored in the blockchain after block formation also just like arrays of bytes. The transaction semantics only make sense for the application once Tendermint delivers a transaction to it. A transaction could (and usually does) change the application state upon being committed and could provide some metadata to verify that it's actually added to the blockchain and applied to the state. However in order to get some trustful information about the committed transaction result one needs to query the blockchain explicitly.

Queries, in comparison with transactions, do not change the state and are not stored in the blockchain. Queries can only be applied to already committed state that's why they could be used in order to get trustful information (signed by quorum during voting for one of existing blocks) just requesting only a single node.

For working with transactions and queries use Python scripts in [`parse`](https://github.com/fluencelabs/tendermint_research/tree/master/parse) directory.

## Making transactions
To set a new key-value mapping use:
```bash
python query.py localhost:46657 tx a/b=10
...
OK
HEIGHT: 2
INFO:   10
```
This would create hierarchical key `a/b` (if necessary) and map it to `10`. `HEIGHT` value could be used later to verify the `INFO` by querying the blockchain.

This script would output the height value corresponding to provided transaction. The height is available upon executing because `query.py` script uses `broadcast_tx_commit` RPC to send transactions to Tendermint. You can later find the latest transactions by running:
```bash
python parse_chain.py localhost:46657
```
This command would output last 50 non-empty blocks in chain with short summary about transactions. Here you can ensure that provided transaction indeed included in the block with height from response. This fact verifies that Tendermint majority (more than 2/3 of configured validator nodes) agreed on including this transaction in the mentioned block which certified by their signatures. Signature details (including information about all Consensus rounds and phases) can be found by requesting Tendermint RPC:
```bash
curl -s 'localhost:46657/block?height=_' # replace _ with actual height number
```

`get` transaction allows to copy a value from one key to another:
```bash
python query.py localhost:46657 tx a/c=get:a/b
...
INFO:   10
```

Submitting an `increment` transaction would increment the referenced key value and copy the old referenced key value to target key:
```bash
python query.py localhost:46657 tx a/d=increment:a/c
...
INFO:   10
```
To prevent Tendermint from declining transaction that repeats one of the previous applied transactions, it's possible to put any characters after `###` at the end of transaction string, this part of string would be ignored:
```bash
python query.py localhost:46657 tx a/d=increment:a/c###again
...
INFO:   11
```

`sum` transaction would sum the values of references keys and assign the result to the target key:
```bash
python query.py localhost:46657 tx a/e=sum:a/c,a/d
...
INFO:   23
```

`factorial` transaction would calculate the factorial of the referenced key value:
```bash
python query.py localhost:46657 tx a/f=factorial:a/b
...
INFO:   3628800
```

`hiersum` transaction would calculate the sum of non-empty values for the referenced key and its descendants by hierarchy (all non-empty values should be integer):
```bash
python query.py localhost:46657 tx c/asum=hiersum:a
...
INFO:   3628856
```

Transactions are not applied in case of wrong arguments (non-integer values to `increment`, `sum`, `factorial` or wrong number of arguments). Transactions with a target key like `get`, `increment`, `sum`, `factorial` return the new value of the target key as `INFO`, but this values cannot be trusted if the serving node is not reliable. To verify the returned `INFO` one needs to `query` the target key explicitly.

In case of massive broadcasting of multiple transactions via `broadcast_tx_sync` or `broadcast_tx_async` RPC, the app would not calculate Merkle hashes during `DeliverTx` processing. Instead it would modify key tree and mark changed paths by clearing Merkle hashes until ABCI `Commit` processing. On `Commit` the app would recalculate Merkle hash along changed paths only. Finally the app would return the resulting root Merkle hash to Tendermint and this hash would be stored as `app_hash` for corresponding height in the blockchain.

Note that described merkelized structure is just for demo purposes and not self-balanced, it would remain efficient only until it the user transactions keep it relatively balanced. Something like [Patricia tree](https://github.com/ethereum/wiki/wiki/Patricia-Tree) should be more appropriate to achieve self-balancing.

## Making queries
Use `get:` queries to read values from KVStore:
```bash
python query.py localhost:46657 query get:a/e
...
RESULT: 23
```
Use `ls:` queries to read key hierarchy:
```bash
python query.py localhost:46657 query ls:a
...
RESULT: e f b c d
```
These commands implemented by requesting `abci_query` RPC (which immediately proxies to ABCI `Query` in the app). Together with requested information the app method would return Merkle proof of this information. This Merkle proof is comma-separated list (`<level-1-proof>,<level-2-proof>,...`) of level proofs along the path to the requested key. For this implementation SHA-3 of a level in the list is exactly:
* either one of the space-separated item from the upper (the previous in comma-separated list) level proof;
* or the root app hash for the uppermost (the first) level proof.

The app stores historical changes and handle queries for any particular height. The requested height (the latest by default) and the corresponding `app_hash` also returned for `query` Python script. This combination (result, Merkle proof and `app_hash` from the blockchain) verifies the correctness of the result (because this `app_hash` could only appear in the blockchain as a result of Tendermint quorum consistent decision).

## Heavy-weight transactions
Applying simple transactions with different target keys makes the sizes of the blockchain (which contains transaction list) and the app state relatively close to each other. If target keys are often repeated, the blockchain size would become much larger than the app state size. To demonstrate the opposite situating (the app state much larger than the blockchain) *range* transactions are supported:
```bash
python query.py localhost:46657 tx 0-200:b/@1/@0=1
...
INFO:   1
```
Here `0-200:` prefix means that this transaction should consist of 200 subsequent key-value mappings, each of them obtained by applying a template `b/@1/@0=1` to a counter from 0 to 199, inclusive. `@0` and `@1` are substitution markers for the two lowermost hexadecimal digits of the counter. I. e. this transaction would create 200 keys: `b/0/0`, `b/0/1`, ..., `b/c/7` and put `1` to each of them.

We can check the result by querying the hierarchical sum of `b` children:
```bash
python query.py localhost:46657 tx c/bsum=hiersum:b
...
INFO:   200
```