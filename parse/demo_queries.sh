python query.py localhost:46257 tx a/b=10
python query.py localhost:46257 tx a/c=get:a/b
python query.py localhost:46257 tx a/d=increment:a/c
python query.py localhost:46257 tx a/d=increment:a/c###again
python query.py localhost:46257 tx a/e=sum:a/c,a/d
python query.py localhost:46257 tx a/f=factorial:a/b
python query.py localhost:46257 tx c/asum=hiersum:a
python query.py localhost:46257 query get:a/e
python query.py localhost:46257 tx 0-200:b/@1/@0=1
python query.py localhost:46257 tx c/bsum=hiersum:b