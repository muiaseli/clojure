/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

/* rich Jul 5, 2007 */

package clojure.lang;

import java.util.List;
import java.util.Random;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class PersistentVector extends APersistentVector implements IEditableCollection{

static class Node{
	final AtomicBoolean edit;
	final Object[] array;

	Node(AtomicBoolean edit, Object[] array){
		this.edit = edit;
		this.array = array;
	}

	Node(AtomicBoolean edit){
		this.edit = edit;
		this.array = new Object[32];
	}
}

final static AtomicBoolean NOEDIT = new AtomicBoolean(false);
final static Node EMPTY_NODE = new Node(NOEDIT, new Object[32]);

final int cnt;
final int shift;
final Node root;
final Object[] tail;

public final static PersistentVector EMPTY = new PersistentVector(0, 5, EMPTY_NODE, new Object[]{});

static public PersistentVector create(ISeq items){
	PersistentVector ret = EMPTY;
	for(; items != null; items = items.next())
		ret = ret.cons(items.first());
	return ret;
}

static public PersistentVector create(List items){
	PersistentVector ret = EMPTY;
	for(Object item : items)
		ret = ret.cons(item);
	return ret;
}

static public PersistentVector create(Object... items){
	PersistentVector ret = EMPTY;
	for(Object item : items)
		ret = ret.cons(item);
	return ret;
}

PersistentVector(int cnt, int shift, Node root, Object[] tail){
	super(null);
	this.cnt = cnt;
	this.shift = shift;
	this.root = root;
	this.tail = tail;
}


PersistentVector(IPersistentMap meta, int cnt, int shift, Node root, Object[] tail){
	super(meta);
	this.cnt = cnt;
	this.shift = shift;
	this.root = root;
	this.tail = tail;
}

public Mutable mutable(){
	return new Mutable(this);
}

final int tailoff(){
	if(cnt < 32)
		return 0;
	return ((cnt - 1) >>> 5) << 5;
}

public Object[] arrayFor(int i){
	if(i >= 0 && i < cnt)
		{
		if(i >= tailoff())
			return tail;
		Node node = root;
		for(int level = shift; level > 0; level -= 5)
			node = (Node) node.array[(i >>> level) & 0x01f];
		return node.array;
		}
	throw new IndexOutOfBoundsException();
}

public Object nth(int i){
	Object[] node = arrayFor(i);
	return node[i & 0x01f];
}

public PersistentVector assocN(int i, Object val){
	if(i >= 0 && i < cnt)
		{
		if(i >= tailoff())
			{
			Object[] newTail = new Object[tail.length];
			System.arraycopy(tail, 0, newTail, 0, tail.length);
			newTail[i & 0x01f] = val;

			return new PersistentVector(meta(), cnt, shift, root, newTail);
			}

		return new PersistentVector(meta(), cnt, shift, doAssoc(shift, root, i, val), tail);
		}
	if(i == cnt)
		return cons(val);
	throw new IndexOutOfBoundsException();
}

private static Node doAssoc(int level, Node node, int i, Object val){
	Node ret = new Node(node.edit,node.array.clone());
	if(level == 0)
		{
		ret.array[i & 0x01f] = val;
		}
	else
		{
		int subidx = (i >>> level) & 0x01f;
		ret.array[subidx] = doAssoc(level - 5, (Node) node.array[subidx], i, val);
		}
	return ret;
}

public int count(){
	return cnt;
}

public PersistentVector withMeta(IPersistentMap meta){
	return new PersistentVector(meta, cnt, shift, root, tail);
}


public PersistentVector cons(Object val){
	int i = cnt;
	//room in tail?
//	if(tail.length < 32)
	if(cnt - tailoff() < 32)
		{
		Object[] newTail = new Object[tail.length + 1];
		System.arraycopy(tail, 0, newTail, 0, tail.length);
		newTail[tail.length] = val;
		return new PersistentVector(meta(), cnt + 1, shift, root, newTail);
		}
	//full tail, push into tree
	Node newroot;
	Node tailnode = new Node(root.edit,tail);
	int newshift = shift;
	//overflow root?
	if((cnt >>> 5) > (1 << shift))
		{
		newroot = new Node(root.edit);
		newroot.array[0] = root;
		newroot.array[1] = newPath(root.edit,shift, tailnode);
		newshift += 5;
		}
	else
		newroot = pushTail(shift, root, tailnode);
	return new PersistentVector(meta(), cnt + 1, newshift, newroot, new Object[]{val});
}

private Node pushTail(int level, Node parent, Node tailnode){
	//if parent is leaf, insert node,
	// else does it map to an existing child? -> nodeToInsert = pushNode one more level
	// else alloc new path
	//return  nodeToInsert placed in copy of parent
	int subidx = ((cnt - 1) >>> level) & 0x01f;
	Node ret = new Node(parent.edit, parent.array.clone());
	Node nodeToInsert;
	if(level == 5)
		{
		nodeToInsert = tailnode;
		}
	else
		{
		Node child = (Node) parent.array[subidx];
		nodeToInsert = (child != null)?
		                pushTail(level-5,child, tailnode)
		                :newPath(root.edit,level-5, tailnode);
		}
	ret.array[subidx] = nodeToInsert;
	return ret;
}

private static Node newPath(AtomicBoolean edit,int level, Node node){
	if(level == 0)
		return node;
	Node ret = new Node(edit);
	ret.array[0] = newPath(edit, level - 5, node);
	return ret;
}

public IChunkedSeq chunkedSeq(){
	if(count() == 0)
		return null;
	return new ChunkedSeq(this,0,0);
}

public ISeq seq(){
	return chunkedSeq();
}

static public final class ChunkedSeq extends ASeq implements IChunkedSeq{

	final PersistentVector vec;
	final Object[] node;
	final int i;
	final int offset;

	public ChunkedSeq(PersistentVector vec, int i, int offset){
		this.vec = vec;
		this.i = i;
		this.offset = offset;
		this.node = vec.arrayFor(i);
	}

	ChunkedSeq(IPersistentMap meta, PersistentVector vec, Object[] node, int i, int offset){
		super(meta);
		this.vec = vec;
		this.node = node;
		this.i = i;
		this.offset = offset;
	}

	ChunkedSeq(PersistentVector vec, Object[] node, int i, int offset){
		this.vec = vec;
		this.node = node;
		this.i = i;
		this.offset = offset;
	}

	public IChunk chunkedFirst() throws Exception{
		return new ArrayChunk(node, offset);
		}

	public ISeq chunkedNext(){
		if(i + node.length < vec.cnt)
			return new ChunkedSeq(vec,i+ node.length,0);
		return null;
		}

	public ISeq chunkedMore(){
		ISeq s = chunkedNext();
		if(s == null)
			return PersistentList.EMPTY;
		return s;
	}

	public Obj withMeta(IPersistentMap meta){
		if(meta == this._meta)
			return this;
		return new ChunkedSeq(meta, vec, node, i, offset);
	}

	public Object first(){
		return node[offset];
	}

	public ISeq next(){
		if(offset + 1 < node.length)
			return new ChunkedSeq(vec, node, i, offset + 1);
		return chunkedNext();
	}
}

public IPersistentCollection empty(){
	return EMPTY.withMeta(meta());
}

//private Node pushTail(int level, Node node, Object[] tailNode, Box expansion){
//	Object newchild;
//	if(level == 0)
//		{
//		newchild = tailNode;
//		}
//	else
//		{
//		newchild = pushTail(level - 5, (Object[]) arr[arr.length - 1], tailNode, expansion);
//		if(expansion.val == null)
//			{
//			Object[] ret = arr.clone();
//			ret[arr.length - 1] = newchild;
//			return ret;
//			}
//		else
//			newchild = expansion.val;
//		}
//	//expansion
//	if(arr.length == 32)
//		{
//		expansion.val = new Object[]{newchild};
//		return arr;
//		}
//	Object[] ret = new Object[arr.length + 1];
//	System.arraycopy(arr, 0, ret, 0, arr.length);
//	ret[arr.length] = newchild;
//	expansion.val = null;
//	return ret;
//}

public PersistentVector pop(){
	if(cnt == 0)
		throw new IllegalStateException("Can't pop empty vector");
	if(cnt == 1)
		return EMPTY.withMeta(meta());
	//if(tail.length > 1)
	if(cnt-tailoff() > 1)
		{
		Object[] newTail = new Object[tail.length - 1];
		System.arraycopy(tail, 0, newTail, 0, newTail.length);
		return new PersistentVector(meta(), cnt - 1, shift, root, newTail);
		}
	Object[] newtail = arrayFor(cnt - 2);

	Node newroot = popTail(shift, root);
	int newshift = shift;
	if(newroot == null)
		{
		newroot = EMPTY_NODE;
		}
	if(shift > 5 && newroot.array[1] == null)
		{
		newroot = (Node) newroot.array[0];
		newshift -= 5;
		}
	return new PersistentVector(meta(), cnt - 1, newshift, newroot, newtail);
}

private Node popTail(int level, Node node){
	int subidx = ((cnt-2) >>> level) & 0x01f;
	if(level > 5)
		{
		Node newchild = popTail(level - 5, (Node) node.array[subidx]);
		if(newchild == null && subidx == 0)
			return null;
		else
			{
			Node ret = new Node(root.edit, node.array.clone());
			ret.array[subidx] = newchild;
			return ret;
			}
		}
	else if(subidx == 0)
		return null;
	else
		{
		Node ret = new Node(root.edit, node.array.clone());
		ret.array[subidx] = null;
		return ret;
		}
}

static class Mutable implements IMutableVector, Counted{
	int cnt;
	int shift;
	Node root;
	Object[] tail;

	Mutable(int cnt, int shift, Node root, Object[] tail){
		this.cnt = cnt;
		this.shift = shift;
		this.root = root;
		this.tail = tail;
	}

	Mutable(PersistentVector v){
		this(v.cnt, v.shift, editable(v.root), editableTail(v.tail));
	}

	public int count(){
		return cnt;
	}
	
	Node ensureEditable(Node node){
		if(node.edit == root.edit)
			return node;
		return new Node(root.edit, node.array.clone());
	}

	void ensureEditable(){
		if(root.edit.get())
			return;
		root = editable(root);
		tail = editableTail(tail);
	}

	static Node editable(Node node){
		return new Node(new AtomicBoolean(true), node.array.clone());
	}

	public PersistentVector immutable(){
		root.edit.set(false);
		return new PersistentVector(cnt, shift, root, tail);
	}

	static Object[] editableTail(Object[] tl){
		Object[] ret = new Object[32];
		System.arraycopy(tl,0,ret,0,tl.length);
		return ret;
	}

	public Mutable conj(Object val){
		ensureEditable();
		int i = cnt;
		//room in tail?
		if(i - tailoff() < 32)
			{
			tail[i & 0x01f] = val;
			++cnt;
			return this;
			}
		//full tail, push into tree
		Node newroot;
		Node tailnode = new Node(root.edit, tail);
		tail = new Object[32];
		tail[0] = val;
		int newshift = shift;
		//overflow root?
		if((cnt >>> 5) > (1 << shift))
			{
			newroot = new Node(root.edit);
			newroot.array[0] = root;
			newroot.array[1] = newPath(root.edit,shift, tailnode);
			newshift += 5;
			}
		else
			newroot = pushTail(shift, root, tailnode);
		root = newroot;
		shift = newshift;
		++cnt;
		return this;
	}

	private Node pushTail(int level, Node parent, Node tailnode){
		//if parent is leaf, insert node,
		// else does it map to an existing child? -> nodeToInsert = pushNode one more level
		// else alloc new path
		//return  nodeToInsert placed in parent
		parent = ensureEditable(parent);
		int subidx = ((cnt - 1) >>> level) & 0x01f;
		Node ret = parent;
		Node nodeToInsert;
		if(level == 5)
			{
			nodeToInsert = tailnode;
			}
		else
			{
			Node child = (Node) parent.array[subidx];
			nodeToInsert = (child != null) ?
			               pushTail(level - 5, child, tailnode)
			                               : newPath(root.edit, level - 5, tailnode);
			}
		ret.array[subidx] = nodeToInsert;
		return ret;
	}

	final int tailoff(){
		if(cnt < 32)
			return 0;
		return ((cnt-1) >>> 5) << 5;
	}

	private Object[] arrayFor(int i){
		if(i >= 0 && i < cnt)
			{
			if(i >= tailoff())
				return tail;
			Node node = root;
			for(int level = shift; level > 0; level -= 5)
				node = (Node) node.array[(i >>> level) & 0x01f];
			return node.array;
			}
		throw new IndexOutOfBoundsException();
	}

	public Object valAt(Object key){
		if(Util.isInteger(key))
			{
			int i = ((Number) key).intValue();
			if(i >= 0 && i < count())
				return nth(i);
			}
		return null;
	}

	public Object nth(int i){
		Object[] node = arrayFor(i);
		return node[i & 0x01f];
	}

	public Mutable assocN(int i, Object val){
		ensureEditable();
		if(i >= 0 && i < cnt)
			{
			if(i >= tailoff())
				{
				tail[i & 0x01f] = val;
				return this;
				}

			root = doAssoc(shift, root, i, val);
			return this;
			}
		if(i == cnt)
			return conj(val);
		throw new IndexOutOfBoundsException();
	}

	public Mutable assoc(Object key, Object val){
		if(Util.isInteger(key))
			{
			int i = ((Number) key).intValue();
			return assocN(i, val);
			}
		throw new IllegalArgumentException("Key must be integer");
	}

	private Node doAssoc(int level, Node node, int i, Object val){
		node = ensureEditable(node);
		Node ret = node;
		if(level == 0)
			{
			ret.array[i & 0x01f] = val;
			}
		else
			{
			int subidx = (i >>> level) & 0x01f;
			ret.array[subidx] = doAssoc(level - 5, (Node) node.array[subidx], i, val);
			}
		return ret;
	}

	public Mutable pop(){
		ensureEditable();
		if(cnt == 0)
			throw new IllegalStateException("Can't pop empty vector");
		if(cnt == 1)
			{
			cnt = 0;
			return this;
			}
		int i = cnt - 1;
		//pop in tail?
		if((i & 0x01f) > 0)
			{
			--cnt;
			return this;
			}

		Object[] newtail = arrayFor(cnt - 2);

		Node newroot = popTail(shift, root);
		int newshift = shift;
		if(newroot == null)
			{
			newroot = EMPTY_NODE;
			}
		if(shift > 5 && newroot.array[1] == null)
			{
			newroot = (Node) newroot.array[0];
			newshift -= 5;
			}
		root = newroot;
		shift = newshift;
		--cnt;
		tail = newtail;
		return this;
	}

	private Node popTail(int level, Node node){
		node = ensureEditable(node);
		int subidx = ((cnt - 2) >>> level) & 0x01f;
		if(level > 5)
			{
			Node newchild = popTail(level - 5, (Node) node.array[subidx]);
			if(newchild == null && subidx == 0)
				return null;
			else
				{
				Node ret = node;
				ret.array[subidx] = newchild;
				return ret;
				}
			}
		else if(subidx == 0)
			return null;
		else
			{
			Node ret = node;
			ret.array[subidx] = null;
			return ret;
			}
	}
}
//*
static public void main(String[] args){
	if(args.length != 3)
		{
		System.err.println("Usage: PersistentVector size writes reads");
		return;
		}
	int size = Integer.parseInt(args[0]);
	int writes = Integer.parseInt(args[1]);
	int reads = Integer.parseInt(args[2]);
	//Vector v = new Vector(size);
	ArrayList v = new ArrayList(size);
	//v.setSize(size);
	//PersistentArray p = new PersistentArray(size);
	PersistentVector p = PersistentVector.EMPTY;
	Mutable mp = p.mutable();

	for(int i = 0; i < size; i++)
		{
		v.add(i);
//		v.set(i, i);
		//p = p.set(i, 0);
//		p = p.cons(i);
		mp = mp.conj(i);
		}

	Random rand;

	rand = new Random(42);
	long tv = 0;
	System.out.println("ArrayList");
	long startTime = System.nanoTime();
	for(int i = 0; i < writes; i++)
		{
		v.set(rand.nextInt(size), i);
		}
	for(int i = 0; i < reads; i++)
		{
		tv += (Integer) v.get(rand.nextInt(size));
		}
	long estimatedTime = System.nanoTime() - startTime;
	System.out.println("time: " + estimatedTime / 1000000);
	System.out.println("PersistentVector");
	rand = new Random(42);
	startTime = System.nanoTime();
	long tp = 0;

//	PersistentVector oldp = p;
	//Random rand2 = new Random(42);

//	IMutableVector mp = p.mutable();
	for(int i = 0; i < writes; i++)
		{
//		p = p.assocN(rand.nextInt(size), i);
		mp = mp.assocN(rand.nextInt(size), i);
//		mp = mp.assoc(rand.nextInt(size), i);
		//dummy set to force perverse branching
		//oldp =	oldp.assocN(rand2.nextInt(size), i);
		}
	for(int i = 0; i < reads; i++)
		{
//		tp += (Integer) p.nth(rand.nextInt(size));
		tp += (Integer) mp.nth(rand.nextInt(size));
		}
//	p = mp.immutable();
	//mp.cons(42);
	estimatedTime = System.nanoTime() - startTime;
	System.out.println("time: " + estimatedTime / 1000000);
	for(int i = 0; i < size / 2; i++)
		{
		mp = mp.pop();
//		p = p.pop();
		v.remove(v.size() - 1);
		}
	p = (PersistentVector) mp.immutable();
	for(int i = 0; i < size / 2; i++)
		{
		tp += (Integer) p.nth(i);
		tv += (Integer) v.get(i);
		}
	System.out.println("Done: " + tv + ", " + tp);

}
//  */
}
