#!/bin/bash
python block_report.py $1 "$2" $3 $4 | tee "$2.txt"