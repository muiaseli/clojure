/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Common Public License 1.0 (http://opensource.org/licenses/cpl.php)
 *   which can be found in the file CPL.TXT at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

/* rich Jun 18, 2007 */

package clojure.lang;

import java.util.*;
//this stuff is just for the test main()
import java.util.regex.Pattern;
import java.io.File;
import java.io.FileNotFoundException;

/*
 A persistent rendition of Phil Bagwell's Ideal Hash Trees

 Uses path copying for persistence
 HashCollision leaves vs. extended hashing
 Node polymorphism vs. conditionals
 No sub-tree pools or root-resizing
 Any errors are my own
 */

public class PersistentHashMap extends APersistentMap{

final int count;
final INode root;

final public static PersistentHashMap EMPTY = new PersistentHashMap(0, new EmptyNode());

/**
 * @param init {key1,val1,key2,val2,...}
 */
public static IPersistentMap create(Object... init){
	IPersistentMap ret = EMPTY;
	for(int i = 0; i < init.length; i += 2)
		{
		ret = ret.assoc(init[i],init[i + 1]);
		}
	return ret;
}

/**
 * @param init {key1,val1,key2,val2,...}
 */
public static IPersistentMap create(IPersistentMap meta,Object... init){
	IPersistentMap ret = (IPersistentMap) EMPTY.withMeta(meta);
	for(int i = 0; i < init.length; i += 2)
		{
		ret = ret.assoc(init[i],init[i + 1]);
		}
	return ret;
}
PersistentHashMap(int count, INode root){
	this.count = count;
	this.root = root;
}


public PersistentHashMap(IPersistentMap meta, int count, INode root){
	super(meta);
	this.count = count;
	this.root = root;
}

public boolean contains(Object key){
	return entryAt(key) != null;
}

public IMapEntry entryAt(Object key){
	return root.find(RT.hash(key), key);
}

public IPersistentMap assoc(Object key, Object val){
	Box addedLeaf = new Box(null);
	INode newroot = root.assoc(0, RT.hash(key), key, val, addedLeaf);
	if(newroot == root)
		return this;
	return new PersistentHashMap(meta(),addedLeaf.val == null ? count : count + 1, newroot);
}

public Object valAt(Object key){
	IMapEntry e = entryAt(key);
	if(e != null)
		return e.val();
	return null;
}

public IPersistentMap assocEx(Object key, Object val) throws Exception{
	if(contains(key))
		throw new Exception("Key already present");
	return assoc(key, val);
}

public IPersistentMap without(Object key){
	INode newroot = root.without(RT.hash(key), key);
	if(newroot == root)
		return this;
	if(newroot == null)
		return (IPersistentMap) EMPTY.withMeta(meta());
	return new PersistentHashMap(meta(),count - 1, newroot);
}

public Iterator iterator(){
	return new SeqIterator(seq());
}

public int count(){
	return count;
}

public ISeq seq(){
	return root.seq();
}

static interface INode{
	INode assoc(int shift, int hash, Object key, Object val, Box addedLeaf);

	INode without(int hash, Object key);

	LeafNode find(int hash, Object key);

	ISeq seq();
}

static interface ILeaf extends INode{
	int getHash();
}

final static class EmptyNode implements INode{

	public INode assoc(int shift, int hash, Object key, Object val, Box addedLeaf){
		INode ret = new LeafNode(hash, key, val);
		addedLeaf.val = ret;
		return ret;
	}

	public INode without(int hash, Object key){
		return this;
	}

	public LeafNode find(int hash, Object key){
		return null;
	}

	public ISeq seq(){
		return null;
	}
}

final static class FullNode implements INode{
	final INode[] nodes;
	final int shift;

	static int mask(int hash, int shift){
		return (hash >>> shift) & 0x01f;
	}

	static int bitpos(int hash, int shift){
		return 1 << mask(hash, shift);
	}

	FullNode(INode[] nodes, int shift){
		this.nodes = nodes;
		this.shift = shift;
	}

	public INode assoc(int shift, int hash, Object key, Object val, Box addedLeaf){
		int idx = mask(hash, shift);

		INode n = nodes[idx].assoc(shift + 5, hash, key, val, addedLeaf);
		if(n == nodes[idx])
			return this;
		else
			{
			INode[] newnodes = nodes.clone();
			newnodes[idx] = n;
			return new FullNode(newnodes, shift);
			}
	}

	public INode without(int hash, Object key){

		int idx = mask(hash, shift);
		INode n = nodes[idx].without(hash, key);
		if(n != nodes[idx])
			{
			if(n == null)
				{
				INode[] newnodes = new INode[nodes.length - 1];
				System.arraycopy(nodes, 0, newnodes, 0, idx);
				System.arraycopy(nodes, idx + 1, newnodes, idx, nodes.length - (idx + 1));
				return new BitmapIndexedNode(~bitpos(hash, shift), newnodes, shift);
				}
			INode[] newnodes = nodes.clone();
			newnodes[idx] = n;
			return new FullNode(newnodes, shift);
			}
		return this;
	}

	public LeafNode find(int hash, Object key){
		return nodes[mask(hash, shift)].find(hash, key);
	}

	public ISeq seq(){
		return Seq.create(this, 0);
	}

	static class Seq extends ASeq{
		final ISeq s;
		final int i;
		final FullNode node;


		Seq(ISeq s, int i, FullNode node){
			this.s = s;
			this.i = i;
			this.node = node;
		}

		static ISeq create(FullNode node, int i){
			if(i >= node.nodes.length)
				return null;
			return new Seq(node.nodes[i].seq(), i, node);
		}

		public Object first(){
			return s.first();
		}

		public ISeq rest(){
			ISeq nexts = s.rest();
			if(nexts != null)
				return new Seq(nexts, i, node);
			return create(node, i + 1);
		}
	}


}

final static class BitmapIndexedNode implements INode{
	final int bitmap;
	final INode[] nodes;
	final int shift;

	static int mask(int hash, int shift){
		return (hash >>> shift) & 0x01f;
	}

	static int bitpos(int hash, int shift){
		return 1 << mask(hash, shift);
	}

	final int index(int bit){
		return Integer.bitCount(bitmap & (bit - 1));
	}


	BitmapIndexedNode(int bitmap, INode[] nodes, int shift){
		this.bitmap = bitmap;
		this.nodes = nodes;
		this.shift = shift;
	}

	static INode create(int bitmap, INode[] nodes, int shift){
		if(bitmap == -1)
			return new FullNode(nodes, shift);
		return new BitmapIndexedNode(bitmap, nodes, shift);
	}

	static INode create(int shift, ILeaf leaf, int hash, Object key, Object val, Box addedLeaf){
		return (new BitmapIndexedNode(bitpos(leaf.getHash(), shift), new INode[]{leaf}, shift))
				.assoc(shift, hash, key, val, addedLeaf);
	}

	public INode assoc(int shift, int hash, Object key, Object val, Box addedLeaf){
		int bit = bitpos(hash, shift);
		int idx = index(bit);
		if((bitmap & bit) != 0)
			{
			INode n = nodes[idx].assoc(shift + 5, hash, key, val, addedLeaf);
			if(n == nodes[idx])
				return this;
			else
				{
				INode[] newnodes = nodes.clone();
				newnodes[idx] = n;
				return new BitmapIndexedNode(bitmap, newnodes, shift);
				}
			}
		else
			{
			INode[] newnodes = new INode[nodes.length + 1];
			System.arraycopy(nodes, 0, newnodes, 0, idx);
			addedLeaf.val = newnodes[idx] = new LeafNode(hash, key, val);
			System.arraycopy(nodes, idx, newnodes, idx + 1, nodes.length - idx);
			return create(bitmap | bit, newnodes, shift);
			}
	}

	public INode without(int hash, Object key){
		int bit = bitpos(hash, shift);
		if((bitmap & bit) != 0)
			{
			int idx = index(bit);
			INode n = nodes[idx].without(hash, key);
			if(n != nodes[idx])
				{
				if(n == null)
					{
					if(bitmap == bit)
						return null;
					INode[] newnodes = new INode[nodes.length - 1];
					System.arraycopy(nodes, 0, newnodes, 0, idx);
					System.arraycopy(nodes, idx + 1, newnodes, idx, nodes.length - (idx + 1));
					return new BitmapIndexedNode(bitmap & ~bit, newnodes, shift);
					}
				INode[] newnodes = nodes.clone();
				newnodes[idx] = n;
				return new BitmapIndexedNode(bitmap, newnodes, shift);
				}
			}
		return this;
	}

	public LeafNode find(int hash, Object key){
		int bit = bitpos(hash, shift);
		if((bitmap & bit) != 0)
			{
			return nodes[index(bit)].find(hash, key);
			}
		else
			return null;
	}

	public ISeq seq(){
		return Seq.create(this, 0);
	}

	static class Seq extends ASeq{
		final ISeq s;
		final int i;
		final BitmapIndexedNode node;


		Seq(ISeq s, int i, BitmapIndexedNode node){
			this.s = s;
			this.i = i;
			this.node = node;
		}

		static ISeq create(BitmapIndexedNode node, int i){
			if(i >= node.nodes.length)
				return null;
			return new Seq(node.nodes[i].seq(), i, node);
		}

		public Object first(){
			return s.first();
		}

		public ISeq rest(){
			ISeq nexts = s.rest();
			if(nexts != null)
				return new Seq(nexts, i, node);
			return create(node, i + 1);
		}
	}


}

final static class LeafNode implements ILeaf, IMapEntry{
	final int hash;
	final Object key;
	final Object val;

	public LeafNode(int hash, Object key, Object val){
		this.hash = hash;
		this.key = key;
		this.val = val;
	}

	public INode assoc(int shift, int hash, Object key, Object val, Box addedLeaf){
		if(hash == this.hash)
			{
			if(RT.equal(key, this.key))
				{
				if(RT.equal(val, this.val))
					return this;
				//note  - do not set addedLeaf, since we are replacing
				return new LeafNode(hash, key, val);
				}
			//hash collision - same hash, different keys
			LeafNode newLeaf = new LeafNode(hash, key, val);
			addedLeaf.val = newLeaf;
			return new HashCollisionNode(hash, this, newLeaf);
			}
		return BitmapIndexedNode.create(shift, this, hash, key, val, addedLeaf);
	}

	public INode without(int hash, Object key){
		if(hash == this.hash && RT.equal(key, this.key))
			return null;
		return this;
	}

	public LeafNode find(int hash, Object key){
		if(hash == this.hash && RT.equal(key, this.key))
			return this;
		return null;
	}

	public ISeq seq(){
		return RT.cons(this, null);
	}

	public int getHash(){
		return hash;
	}

	public Object key(){
		return this.key;
	}

	public Object val(){
		return this.val;
	}

}

final static class HashCollisionNode implements ILeaf{

	final int hash;
	final LeafNode[] leaves;

	public HashCollisionNode(int hash, LeafNode... leaves){
		this.hash = hash;
		this.leaves = leaves;
	}

	public INode assoc(int shift, int hash, Object key, Object val, Box addedLeaf){
		if(hash == this.hash)
			{
			int idx = findIndex(hash, key);
			if(idx != -1)
				{
				if(RT.equal(leaves[idx].val, val))
					return this;
				LeafNode[] newLeaves = leaves.clone();
				//note  - do not set addedLeaf, since we are replacing
				newLeaves[idx] = new LeafNode(hash, key, val);
				return new HashCollisionNode(hash, newLeaves);
				}
			LeafNode[] newLeaves = new LeafNode[leaves.length + 1];
			System.arraycopy(leaves, 0, newLeaves, 0, leaves.length);
			addedLeaf.val = newLeaves[leaves.length] = new LeafNode(hash, key, val);
			return new HashCollisionNode(hash, newLeaves);
			}
		return BitmapIndexedNode.create(shift, this, hash, key, val, addedLeaf);
	}

	public INode without(int hash, Object key){
		int idx = findIndex(hash, key);
		if(idx == -1)
			return this;
		if(leaves.length == 2)
			return idx == 0 ? leaves[1] : leaves[0];
		LeafNode[] newLeaves = new LeafNode[leaves.length - 1];
		System.arraycopy(leaves, 0, newLeaves, 0, idx);
		System.arraycopy(leaves, idx + 1, newLeaves, idx, leaves.length - (idx + 1));
		return new HashCollisionNode(hash, newLeaves);
	}

	public LeafNode find(int hash, Object key){
		int idx = findIndex(hash, key);
		if(idx != -1)
			return leaves[idx];
		return null;
	}

	public ISeq seq(){
		return ArraySeq.create((Object[])leaves);
	}

	int findIndex(int hash, Object key){
		for(int i = 0; i < leaves.length; i++)
			{
			if(leaves[i].find(hash, key) != null)
				return i;
			}
		return -1;
	}

	public int getHash(){
		return hash;
	}
}

public static void main(String[] args){
	try
		{
		ArrayList words = new ArrayList();
		Scanner s = new Scanner(new File(args[0]));
		s.useDelimiter(Pattern.compile("\\W"));
		while(s.hasNext())
			{
			String word = s.next();
			words.add(word);
			}
		System.out.println("words: " + words.size());
		IPersistentMap map = PersistentHashMap.EMPTY;
		//IPersistentMap map = new PersistentTreeMap();
		//Map ht = new Hashtable();
		Map ht = new HashMap();
		Random rand;

		System.out.println("Building map");
		long startTime = System.nanoTime();
		for(int i = 0; i < words.size(); i++)
			{
			map = map.assoc(words.get(i), words.get(i));
			}
		rand = new Random(42);
		IPersistentMap snapshotMap = map;
		for(int i = 0; i < words.size() / 200; i++)
			{
			map = map.without(words.get(rand.nextInt(words.size() / 2)));
			}
		long estimatedTime = System.nanoTime() - startTime;
		System.out.println("count = " + map.count() + ", time: " + estimatedTime / 1000000);

		System.out.println("Building ht");
		startTime = System.nanoTime();
		for(int i = 0; i < words.size(); i++)
			{
			ht.put(words.get(i), words.get(i));
			}
		rand = new Random(42);
		for(int i = 0; i < words.size() / 200; i++)
			{
			ht.remove(words.get(rand.nextInt(words.size() / 2)));
			}
		estimatedTime = System.nanoTime() - startTime;
		System.out.println("count = " + ht.size() + ", time: " + estimatedTime / 1000000);

		System.out.println("map lookup");
		startTime = System.nanoTime();
		int c = 0;
		for(int i = 0; i < words.size(); i++)
			{
			if(!map.contains(words.get(i)))
				++c;
			}
		estimatedTime = System.nanoTime() - startTime;
		System.out.println("notfound = " + c + ", time: " + estimatedTime / 1000000);
		System.out.println("ht lookup");
		startTime = System.nanoTime();
		c = 0;
		for(int i = 0; i < words.size(); i++)
			{
			if(!ht.containsKey(words.get(i)))
				++c;
			}
		estimatedTime = System.nanoTime() - startTime;
		System.out.println("notfound = " + c + ", time: " + estimatedTime / 1000000);
		System.out.println("snapshotMap lookup");
		startTime = System.nanoTime();
		c = 0;
		for(int i = 0; i < words.size(); i++)
			{
			if(!snapshotMap.contains(words.get(i)))
				++c;
			}
		estimatedTime = System.nanoTime() - startTime;
		System.out.println("notfound = " + c + ", time: " + estimatedTime / 1000000);
		}
	catch(FileNotFoundException e)
		{
		e.printStackTrace();
		}

}
}

