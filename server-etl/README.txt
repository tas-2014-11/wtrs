0. generate raw rrd data (client side)
1. discover client's wtid + batchid
2. cook natural keys from raw
3. load natural keys into dimension tables (with kettle)
4. extract dimension tables (to get surrogate keys)
5. translate raw rrd data natural keys into surrogate keys
6. load fact tables
