#!/bin/bash
cd "D:\dev\PlayerAuction"
echo "eula=true" > run/eula.txt
rm -f "run/config/playerauction/Auction.db"
# Write commands to a temp file, pipe to server
echo "stop" > /tmp/mc_commands.txt
./gradlew runServer < /tmp/mc_commands.txt 2>&1