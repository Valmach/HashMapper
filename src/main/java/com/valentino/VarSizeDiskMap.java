package com.valentino;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;

import com.valentino.bucket.RecordChainNode;
import com.valentino.bucket.WritethruRecordChainNode;
import com.valentino.util.Hash;

/**An implementation that stores "buckets" consisting of single-record pointers,
 * which are chained on collision in a secondary file where data is also stored.*/
public class VarSizeDiskMap extends ADiskMap {

	private static final int PRIMARY_REC_SIZE = 8;
	
	public VarSizeDiskMap(String baseFolderLoc){
		this(baseFolderLoc, 0);
	}
	public VarSizeDiskMap(String baseFolderLoc, long primaryFileLen){
		super(baseFolderLoc, nextPowerOf2(primaryFileLen));
	}

	@Override
	protected void readHeader(){
		secondaryLock.readLock().lock();
		try {
			final long size = secondaryMapper.getLong(0),
					   bucketsInMap = secondaryMapper.getLong(8),
					   lastSecondaryPos = secondaryMapper.getLong(16),
					   rehashComplete = secondaryMapper.getLong(24);
			this.size.set(size);
			this.tableLength = bucketsInMap == 0 ? (primaryMapper.size() / PRIMARY_REC_SIZE) : bucketsInMap;
			this.secondaryWritePos.set(lastSecondaryPos == 0 ? getHeaderSize() : lastSecondaryPos);
			this.rehashComplete.set(rehashComplete);
		} finally {
			secondaryLock.readLock().unlock();
		}
	}

	protected long idxToPos(long idx){
		return idx * PRIMARY_REC_SIZE;
	}
	/**Retrieves a record at the given position from the secondary. Does not
	 * validate the correctness of the position.*/
	protected WritethruRecordChainNode getSecondaryRecord(long pos){
		secondaryLock.readLock().lock();
		try {
			return WritethruRecordChainNode.readRecord(secondaryMapper, pos);
		} finally {
			secondaryLock.readLock().unlock();
		}
	}
	
	/**Allocates sufficient space for the record to be written to secondary
	/* at the returned position.*/
	protected long allocateForRecord(final RecordChainNode record){
		final long recordSize = record.size();
		return allocateSecondary(recordSize);
	}
	
	@Override
	public byte[] get(byte[] k){
		final long hash = Hash.murmurHash(k);
		synchronized(lockForHash(hash)){
			final long idx = idxForHash(hash);
			final long pos = idxToPos(idx);
			final long adr = primaryMapper.getLong(pos);
			if(adr == 0) return null;
			
			WritethruRecordChainNode record = getSecondaryRecord(adr);
			while(true){
				if(record.keyEquals(hash, k)) {
					return record.getVal();
				} else if(record.getNextRecordPos() != 0) {
					record = getSecondaryRecord(record.getNextRecordPos());
				} else return null;
			}
		}
	}
	
	//This is the primary use case for a r/w lock
	@Override
	public byte[] putIfAbsent(byte[] k, byte[] v){
		if(load() > loadRehashThreshold) rehash();

		final long hash = Hash.murmurHash(k);
		final RecordChainNode toWriteBucket = new RecordChainNode(hash, k, v);
		
		synchronized(lockForHash(hash)){
			final long idx = idxForHash(hash);
			final long pos = idxToPos(idx);
			final long adr = primaryMapper.getLong(pos);
			if(adr == 0){
				final long insertPos = allocateForRecord(toWriteBucket);
				WritethruRecordChainNode.writeRecord(toWriteBucket, secondaryMapper, insertPos);
				primaryMapper.putLong(pos, insertPos);
				size.incrementAndGet();
				return null;
			}
			WritethruRecordChainNode bucket = getSecondaryRecord(adr);
			while(true){
				if(bucket.keyEquals(hash, k)) return bucket.getVal();
				else if(bucket.getNextRecordPos() != 0) bucket = getSecondaryRecord(bucket.getNextRecordPos());
				else {
					final long insertPos = allocateForRecord(toWriteBucket);
					WritethruRecordChainNode.writeRecord(toWriteBucket, secondaryMapper, insertPos);
					bucket.setNextRecordPos(insertPos);
					size.incrementAndGet();
					return null;
				}
			}
		}
	}
	
	@Override
	public byte[] put(byte[] k, byte[] v){
		if(load() > loadRehashThreshold) rehash();
		
		final long hash = Hash.murmurHash(k);
		final RecordChainNode toWriteBucket = new RecordChainNode(hash, k, v);
		//We'll be inserting somewhere
		final long insertPos = allocateForRecord(toWriteBucket);
		
		synchronized(lockForHash(hash)){
			final long idx = idxForHash(hash);
			final long pos = idxToPos(idx);
			
			final long adr = primaryMapper.getLong(pos);
			if(adr == 0) {
				WritethruRecordChainNode.writeRecord(toWriteBucket, secondaryMapper, insertPos);
				primaryMapper.putLong(pos, insertPos);
				return null;
			}
			
			WritethruRecordChainNode bucket = getSecondaryRecord(adr);
			WritethruRecordChainNode prev = null;
			while(true){
				if(bucket.keyEquals(hash, k)) {
					toWriteBucket.setNextRecordPos(bucket.getNextRecordPos());
					WritethruRecordChainNode.writeRecord(toWriteBucket, secondaryMapper, insertPos);
					if(prev == null) {
						primaryMapper.putLong(pos, insertPos);
					} else {
						prev.setNextRecordPos(insertPos);						
					}
					return bucket.getVal();
				}
				else if(bucket.getNextRecordPos() != 0) {
					prev = bucket;
					bucket = getSecondaryRecord(bucket.getNextRecordPos());
				}
				else {
					WritethruRecordChainNode.writeRecord(toWriteBucket, secondaryMapper, insertPos);
					bucket.setNextRecordPos(insertPos);
					size.incrementAndGet();
					return null;
				}
			}
		}
	}
	
	@Override
	public byte[] remove(byte[] k){
		final long hash = Hash.murmurHash(k);

		synchronized(lockForHash(hash)){
			final long idx = idxForHash(hash);
			final long pos = idxToPos(idx);
			final long adr = primaryMapper.getLong(pos);
			if(adr == 0) return null;
			
			WritethruRecordChainNode bucket = getSecondaryRecord(adr);
			WritethruRecordChainNode prev = null;
			while(true){
				if(bucket.keyEquals(hash, k)) {
					if(prev == null) primaryMapper.putLong(pos, bucket.getNextRecordPos());
					else prev.setNextRecordPos(bucket.getNextRecordPos());
					size.decrementAndGet();
					return bucket.getVal();
				}
				else if(bucket.getNextRecordPos() != 0) {
					prev = bucket;
					bucket = getSecondaryRecord(bucket.getNextRecordPos());
				}
				else return null;
			}
		}
	}
	
	@Override
	public boolean remove(byte[] k, byte[] v) {
		final long hash = Hash.murmurHash(k);

		synchronized(lockForHash(hash)){
			final long idx = idxForHash(hash);
			final long pos = idxToPos(idx);
			final long adr = primaryMapper.getLong(pos);
			if(adr == 0) return false;
			
			WritethruRecordChainNode bucket = getSecondaryRecord(adr);
			WritethruRecordChainNode prev = null;
			while(true){
				if(bucket.keyEquals(hash, k) && Arrays.equals(v, bucket.getVal())) {
					if(prev == null) primaryMapper.putLong(pos, bucket.getNextRecordPos());
					else prev.setNextRecordPos(bucket.getNextRecordPos());
					size.decrementAndGet();
					return true;
				}
				else if(bucket.getNextRecordPos() != 0) {
					prev = bucket;
					bucket = getSecondaryRecord(bucket.getNextRecordPos());
				}
				else return false;
			}
		}
	}
	
	@Override
	public boolean replace(byte[] k, byte[] prevVal, byte[] newVal) {
		final long hash = Hash.murmurHash(k);
		final RecordChainNode toWriteBucket = new RecordChainNode(hash, k, newVal);
		
		synchronized(lockForHash(hash)){
			final long idx = idxForHash(hash);
			final long pos = idxToPos(idx);
			
			final long adr = primaryMapper.getLong(pos);
			if(adr == 0) return false;
			
			WritethruRecordChainNode bucket = getSecondaryRecord(adr);
			WritethruRecordChainNode prev = null;
			while(true){
				if(bucket.keyEquals(hash, k) && Arrays.equals(bucket.getVal(), prevVal)) {
					final long insertPos = allocateForRecord(toWriteBucket);
					toWriteBucket.setNextRecordPos(bucket.getNextRecordPos());
					WritethruRecordChainNode.writeRecord(toWriteBucket, secondaryMapper, insertPos);
					if(prev == null) {
						primaryMapper.putLong(pos, insertPos);
					} else {
						prev.setNextRecordPos(insertPos);						
					}
					return true;
				}
				else if(bucket.getNextRecordPos() != 0) {
					prev = bucket;
					bucket = getSecondaryRecord(bucket.getNextRecordPos());
				}
				else return false;
			}
		}

	}
	@Override
	public byte[] replace(byte[] k, byte[] v) {
		final long hash = Hash.murmurHash(k);
		final RecordChainNode toWriteBucket = new RecordChainNode(hash, k, v);
		
		synchronized(lockForHash(hash)){
			final long idx = idxForHash(hash);
			final long pos = idxToPos(idx);
			
			final long adr = primaryMapper.getLong(pos);
			if(adr == 0) return null;
			
			WritethruRecordChainNode bucket = getSecondaryRecord(adr);
			WritethruRecordChainNode prev = null;
			while(true){
				if(bucket.keyEquals(hash, k)) {
					final long insertPos = allocateForRecord(toWriteBucket);
					toWriteBucket.setNextRecordPos(bucket.getNextRecordPos());
					WritethruRecordChainNode.writeRecord(toWriteBucket, secondaryMapper, insertPos);
					if(prev == null) {
						primaryMapper.putLong(pos, insertPos);
					} else {
						prev.setNextRecordPos(insertPos);						
					}
					return bucket.getVal();
				}
				else if(bucket.getNextRecordPos() != 0) {
					prev = bucket;
					bucket = getSecondaryRecord(bucket.getNextRecordPos());
				}
				else return null;
			}
		}
	}
	
	@Override
	protected void rehashIdx(long idx){
		final ArrayList<WritethruRecordChainNode> keepBuckets = new ArrayList<WritethruRecordChainNode>();
		final ArrayList<WritethruRecordChainNode> moveBuckets = new ArrayList<WritethruRecordChainNode>();
		
		final long keepIdx = idx, moveIdx = idx + tableLength;
		
		final long addr = primaryMapper.getLong(idxToPos(idx));
		if(addr == 0) return;

		WritethruRecordChainNode bucket = getSecondaryRecord(addr);
		while(true){
			final long newIdx = bucket.getHash() & (tableLength + tableLength - 1L);
			if(newIdx == keepIdx) keepBuckets.add(bucket);
			else if(newIdx == moveIdx) moveBuckets.add(bucket);
			else throw new IllegalStateException("hash:" + bucket.getHash() + ", idx:" + keepIdx + ", newIdx:" + newIdx + ", tableLength:" + tableLength);
			if(bucket.getNextRecordPos() != 0) bucket = getSecondaryRecord(bucket.getNextRecordPos());
			else break;
		}
		//Adjust chains
		primaryMapper.putLong(idxToPos(keepIdx), rewriteChain(keepBuckets));
		primaryMapper.putLong(idxToPos(moveIdx), rewriteChain(moveBuckets));
	}
		
	/**Cause each bucket to point to the subsequent one.  Returns address of original,
	 * or 0 if the list was empty.*/
	protected long rewriteChain(List<WritethruRecordChainNode> buckets){
		if(buckets.isEmpty()) return 0;
		buckets.get(buckets.size() - 1).setNextRecordPos(0);
		for(int i=0; i<buckets.size()-1; i++){
			buckets.get(i).setNextRecordPos(buckets.get(i+1).getPos());
		}
		return buckets.get(0).getPos();
	}
		
	/**We don't try to synchronize this, or even throw a 
	 * ConcurrentModificationException.  You must synchronize externally.
	 * However, by virtue of the way we rewrite data, you will receive data
	 * that was at least valid at one time (ie, not bit-sliced & corrupted).*/
	@Override
	public Iterator<Map.Entry<byte[], byte[]>> iterator(){
		return new Iterator<Map.Entry<byte[],byte[]>>() {
			long nextIdx, nextAddr;
			boolean finished;
			{
				finished = true;
				for(nextIdx = 0; nextIdx < tableLength; nextIdx++){
					nextAddr = primaryMapper.getLong(idxToPos(nextIdx));
					if(nextAddr == 0) continue;
					else {
						finished = false;
						break;
					} 
				}
			}
			private void advance(){
				WritethruRecordChainNode bucket = getSecondaryRecord(nextAddr);
				if(bucket.getNextRecordPos() != 0){
					nextAddr = bucket.getNextRecordPos();
					finished = false;
					return;
				}
				for(nextIdx=nextIdx+1; nextIdx < tableLength; nextIdx++){
					final long pos = idxToPos(nextIdx);
					nextAddr = primaryMapper.getLong(pos);
					if(nextAddr == 0) continue;
					else {
						finished = false;
						return;
					}
				}
				finished = true;
			}
			@Override
			public boolean hasNext() { return !finished; }
			@Override
			public Entry<byte[], byte[]> next() {
				if(finished) throw new NoSuchElementException();
				final WritethruRecordChainNode node = getSecondaryRecord(nextAddr);
				advance();
				return new AbstractMap.SimpleEntry<byte[],byte[]>(node.getKey(), node.getVal());
			}
			/**This could potentially corrupt the map, not just return invalid data.*/
			@Override
			public void remove() { throw new UnsupportedOperationException(); }
		};
	}
}
