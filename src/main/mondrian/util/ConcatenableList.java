/*
* This software is subject to the terms of the Eclipse Public License v1.0
* Agreement, available at the following URL:
* http://www.eclipse.org/legal/epl-v10.html.
* You must accept the terms of that agreement to use this software.
*
* Copyright (c) 2002-2013 Pentaho Corporation..  All rights reserved.
*/

package mondrian.util;

import java.util.*;

/**
 * List backed by a collection of sub-lists.
 *
 * @author Luis F. Canals
 * @since december, 2007
 */
public class ConcatenableList<T> extends AbstractList<T> {
    private static int nextHashCode = 1000;

    // The backing collection of sublists
    private final List<List<T>> lists;

    // List containing all elements from backing lists, populated only after
    // consolidate()
    private List<T> plainList;
    private final int hashCode = nextHashCode++;
    private Iterator<T> getIterator = null;
    private int previousIndex = -200;
    private T previousElement = null;
    private T prePreviousElement = null;

    private Set<T> plainSet = new HashSet<T>();

    /**
     * Creates an empty ConcatenableList.
     */
    public ConcatenableList() {
        this.lists = new ArrayList<List<T>>();
        this.plainList = null;
    }

    public <T2> T2[] toArray(T2[] a) {
        consolidate();
        //noinspection unchecked,SuspiciousToArrayCall
        return (T2[]) plainList.toArray((Object []) a);
    }

    public Object[] toArray() {
        consolidate();
        return plainList.toArray();
    }

    /**
     * Performs a load of all elements into memory, removing sequential
     * access advantages.
     */
    public void consolidate() {
        if (this.plainList == null) {
            this.plainList = new ArrayList<T>();
            for (final List<T> list : lists) {
                this.plainList.addAll(list);
            }
        }
        this.plainSet = new HashSet<>(this.plainList);
    }

    public boolean addAll(final Collection<? extends T> collection) {
        this.plainSet.addAll(collection);
        if (this.plainList == null) {
            final List<T> list = (List<T>) collection;
            return this.lists.add(list);
        } else {
            return this.plainList.addAll(collection);
        }
    }

    public T get(final int index) {
        if (this.plainList == null) {
            if (index == 0) {
                this.getIterator = this.iterator();
                this.previousIndex = index;
                if (this.getIterator.hasNext()) {
                    this.previousElement = this.getIterator.next();
                    return this.previousElement;
                } else {
                    this.getIterator = null;
                    this.previousIndex = -200;
                    throw new IndexOutOfBoundsException(
                        "Index " + index + " out of concatenable list range");
                }
            } else if (this.previousIndex + 1 == index
                && this.getIterator != null)
            {
                this.previousIndex = index;
                if (this.getIterator.hasNext()) {
                    this.prePreviousElement = this.previousElement;
                    this.previousElement = this.getIterator.next();
                    return this.previousElement;
                } else {
                    this.getIterator = null;
                    this.previousIndex = -200;
                    throw new IndexOutOfBoundsException(
                        "Index " + index + " out of concatenable list range");
                }
            } else if (this.previousIndex == index) {
                return this.previousElement;
            } else if (this.previousIndex - 1 == index) {
                return this.prePreviousElement;
            } else {
                this.previousIndex = -200;
                this.getIterator = null;
                final Iterator<T> it = this.iterator();
                if (!it.hasNext()) {
                    throw new IndexOutOfBoundsException(
                        "Index " + index + " out of concatenable list range");
                }
                for (int i = 0; i < index; i++) {
                    if (!it.hasNext()) {
                        throw new IndexOutOfBoundsException(
                            "Index " + index
                            + " out of concatenable list range");
                    }
                    this.prePreviousElement = it.next();
                }
                this.previousElement = it.next();
                this.previousIndex = index;
                this.getIterator = it;
                return this.previousElement;
            }
        } else {
            this.previousElement = this.plainList.get(index);
            return this.previousElement;
        }
    }

    public boolean add(final T t) {
        this.plainSet.add(t);
        if (this.plainList == null) {
            return this.lists.add(Collections.singletonList(t));
        } else {
            return this.plainList.add(t);
        }
    }

    public void add(final int index, final T t) {
        if (this.plainList == null) {
            throw new UnsupportedOperationException();
        } else {
            this.plainSet.add(t);
            this.plainList.add(index, t);
        }
    }

    public T set(final int index, final T t) {
        if (this.plainList == null) {
            throw new UnsupportedOperationException();
        } else {
            this.plainSet.add(t);
            return this.plainList.set(index, t);
        }
    }

    public int size() {
        if (this.plainList == null) {
            // REVIEW: Consider consolidating here. As it stands, this loop is
            // expensive if called often on a lot of small lists. Amortized cost
            // would be lower if we consolidated, or partially consolidated.
            return this.plainSet.size();
        } else {
            return this.plainList.size();
        }
    }

    public Iterator<T> iterator() {
        if (this.plainList == null) {
            return new Iterator<T>() {
                private final Iterator<List<T>> listsIt = lists.iterator();
                private Iterator<T> currentListIt;

                public boolean hasNext() {
                    if (currentListIt == null) {
                        if (listsIt.hasNext()) {
                            currentListIt = listsIt.next().iterator();
                        } else {
                            return false;
                        }
                    }

                    // If the current sub-list iterator has no next, grab the
                    // next sub-list's iterator, and continue until either a
                    // sub-list iterator with a next is found (at which point,
                    // the while loop terminates) or no more sub-lists exist (in
                    // which case, return false).
                    while (!currentListIt.hasNext()) {
                        if (listsIt.hasNext()) {
                            currentListIt = listsIt.next().iterator();
                        } else {
                            return false;
                        }
                    }
                    return currentListIt.hasNext();
                }

                public T next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    } else {
                        return currentListIt.next();
                    }
                }

                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        } else {
            return this.plainList.iterator();
        }
    }

    public boolean isEmpty() {
        if (this.plainList != null) {
            return this.plainList.isEmpty();
        }
        return this.plainSet.isEmpty();
    }

    public void clear() {
        this.plainList = null;
        this.plainSet.clear();
        this.lists.clear();
    }

    public int hashCode() {
        return this.hashCode;
    }

    public boolean contains(Object o) {
        return this.plainSet.contains(o);
    }
}

// End ConcatenableList.java
