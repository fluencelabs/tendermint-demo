kill $(ps aux | grep 'tendermint node' | awk '{print $2}')
kill $(ps aux | grep 'run 46.58' | awk '{print $2}')
kill $(ps aux | grep 'judge' | awk '{print $2}')

rm -rf ~/.tendermint/cluster4/1
rm -rf ~/.tendermint/cluster4/2
rm -rf ~/.tendermint/cluster4/3
rm -rf ~/.tendermint/cluster4/4
