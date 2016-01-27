/*
 * Written by Doug Lea & Mike Duigou with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableSet;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.SortedSet;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Predicate;
import java.util.function.Consumer;

/**
 * A {@link java.util.NavigableSet} that uses an internal
 * {@link CopyOnWriteArrayList} for all of its operations.  Thus, it shares the
 * same basic properties:
 * <ul>
 *  <li>It is best suited for applications in which set sizes generally
 *      stay small, read-only operations vastly outnumber mutative operations,
 *      and you need to prevent interference among threads during traversal.
 *  <li>It is thread-safe.
 *  <li>Mutative operations ({@code add}, {@code set}, {@code remove}, etc.)
 *      are expensive since they usually entail copying the entire underlying
 *      array.
 *  <li>Iterators do not support the mutative {@code remove} operation.
 *  <li>Traversal via iterators is fast and cannot encounter interference from
 *      other threads. Iterators rely on unchanging snapshots of the array at
 *      the time the iterators were constructed.
 * </ul>
 *
 * <p><b>Sample Usage.</b> The following code sketch uses a copy-on-write
 * navigable set to maintain a set of ordered Handler objects that perform some
 * action upon state updates until one of the handlers returns true indicating
 * that the update has been handled.
 *
 *  <pre>{@code
 *  public abstract class Handler implements Comparable<Handler> {
 *      final int priority;
 *
 *      protected Handler(int priority) {
 *          this.priority = priority;
 *      }
 *
 *      // returns true if update has been handled
 *      abstract boolean handle();
 *
 *      // ordered from highest to lowest
 *      public final int compareTo(Handler other) {
 *          return -Integer.compare(priority, other.priority);
 *      }
 *  }
 *
 *  class X {
 *      // Will use "Natural Order" of Comparables
 *      private final CopyOnWriteArrayNavigableSet<Handler> handlers
 *        = new CopyOnWriteArrayNavigableSet<>();
 *      public void addHandler(Handler h) { handlers.add(h); }
 *
 *      private long internalState;
 *      private synchronized void changeState() { internalState = ...; }
 *
 *      public void update() {
 *          changeState();
 *          for (Handler handler : handlers)
 *               if(handler.handle()) break;
 *     }
 *  }}</pre>
 *
 * <p>This class is a member of the
 * <a href="{@docRoot}/../technotes/guides/collections/index.html">
 * Java Collections Framework</a>.
 *
 * @see CopyOnWriteArrayList
 * @since 9
 * @author Doug Lea
 * @author Mike Duigou
 * @param <E> the type of elements held in this collection
 */
public class CopyOnWriteArrayNavigableSet<E> extends AbstractSet<E>
        implements java.io.Serializable, NavigableSet<E> {

    private static final long serialVersionUID = -3680134489612968105L;

    /**
     * Comparator for elements.
     */
    final Comparator<? super E> comparator;

    /**
     * Embedded CopyOnWriteArrayList used to hold the storage of this set.
     */
    final CopyOnWriteArrayList<E> al;

    /**
     * Creates a set using the provided comparator with the initial elements
     * of the provided COWAL.
     *
     * @param comparator
     * @param al
     */
    CopyOnWriteArrayNavigableSet(Comparator<? super E> comparator, CopyOnWriteArrayList<E> al) {
        this.comparator = Objects.requireNonNull(comparator, "comparator");
        this.al = al;
    }

     /**
     * Creates an empty set which can be used for mutually
     * {@link java.lang.Comparable Comparable} objects.
     */
    @SuppressWarnings("unchecked")
    public CopyOnWriteArrayNavigableSet() {
        this((Comparator<? super E>) Comparator.naturalOrder());
    }

   /**
     * Creates an empty set with the specified comparator.
     *
     * @param comparator Used for ordering elements. For
     * {@link java.lang.Comparable Comparable} objects use
     * {@link Comparator#naturalOrder()}
     */
    public CopyOnWriteArrayNavigableSet(Comparator<? super E> comparator) {
        this(comparator, new CopyOnWriteArrayList<>());
    }

    /**
     * Creates a set containing all of unique elements of the specified
     * Iterable. If the source iterable is a {@link SortedSet sorted set} then
     * the same Comparator is used.
     *
     * @implNote This implementation makes makes efficient snapshots without
     * copying elements when the source iterable is also a
     * {@code CopyOnWriteArrayNavigableSet}.
     *
     * @param c the elements to initially contain
     * @throws NullPointerException if the specified collection is null
     */
    @SuppressWarnings("unchecked")
    public CopyOnWriteArrayNavigableSet(Iterable<? extends E> c) {
        if (c.getClass() == CopyOnWriteArrayNavigableSet.class) {
            this.comparator = ((CopyOnWriteArrayNavigableSet<E>) c).comparator;
            this.al = new CopyOnWriteArrayList<>();
            this.al.setArray(((CopyOnWriteArrayNavigableSet) c).al.getArray());
        } else if (c instanceof SortedSet) {
            Comparator<? super E> compare = ((SortedSet<E>) c).comparator();
            this.comparator = compare == null
                    ? (Comparator<? super E>) Comparator.naturalOrder()
                    : compare;
            this.al = new CopyOnWriteArrayList<>(((SortedSet) c));
        } else {
            this.comparator = (Comparator<? super E>) Comparator.naturalOrder();
            this.al = new CopyOnWriteArrayList<>();
            if (c instanceof Collection) {
                CopyOnWriteArrayNavigableSet.addAll(this, ((Collection) c));
            } else {
                for(E e : c) {
                    add(this, e);
                }
            }
        }
    }

    /**
     * Creates a new empty CopyOnWriteArrayNavigableSet using natural order
     * ordering.
     * @param <E> Type of elements
     * @return new CopyOnWriteArrayNavigableSet
     */
    public static <E extends Comparable<? super E>> CopyOnWriteArrayNavigableSet<E> create() {
        return new CopyOnWriteArrayNavigableSet<>();
    }

    /**
     * Creates a new CopyOnWriteArrayNavigableSet of the provided elements using
     * natural order ordering.
     * @param <E> Type of elements
     * @param contents initial elements for the set.
     * @return new CopyOnWriteArrayNavigableSet
     */
    public static <E extends Comparable<? super E>> CopyOnWriteArrayNavigableSet<E> create(Iterable<E> contents) {
        return new CopyOnWriteArrayNavigableSet<>(contents);
    }

    /**
     * Creates a new empty CopyOnWriteArrayNavigableSet using provided
     * comparator for ordering.
     * @param <E> Type of elements
     * @param comparator The comparator to use for ordering.
     * @return new CopyOnWriteArrayNavigableSet
     */
    public static <E> CopyOnWriteArrayNavigableSet<E> create(Comparator<? super E> comparator) {
        return new CopyOnWriteArrayNavigableSet<>(comparator);
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean contains(Object o) {
        return Arrays.binarySearch((E[]) al.getArray(), (E) o, comparator) >= 0;
    }

    @Override
    public boolean remove(Object o) {
        synchronized(al.lock) {
            @SuppressWarnings("unchecked")
            E[] array = (E[]) al.getArray();
            @SuppressWarnings("unchecked")
            int loc = Arrays.binarySearch(array, (E) o, comparator);
            if(loc >= 0) {
                al.remove(loc);
                return true;
            }
            return false;
        }
    }

    @Override
    public boolean add(E e) {
        return add(this, e);
    }

    private static <E> boolean add(CopyOnWriteArrayNavigableSet<E> cowans, E e) {
        Objects.requireNonNull(e, "e");
        synchronized(cowans.al.lock) {
            @SuppressWarnings("unchecked")
            E[] array = (E[]) cowans.al.getArray();
            int loc = (array.length != 0)
                    ? Arrays.binarySearch(array, e, cowans.comparator)
                    : cowans.comparator().compare(e, e) == 0 ? -1 : Integer.MIN_VALUE;
            if(loc < 0) {
                // MIN_VALUE is an impossible index we use as a sentinel for bad comparator.
                if (loc == Integer.MIN_VALUE)
                    throw new IllegalArgumentException(
                    "Comparison method violates its general contract!");
                cowans.al.add(-1 - loc, e);
                return true;
            }
            return false;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean containsAll(Collection<?> c) {
        @SuppressWarnings("unchecked")
        E[] array = (E[]) al.getArray();
        for(Object each : c) {
            if(Arrays.binarySearch(array, (E) each, comparator) < 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        return CopyOnWriteArrayNavigableSet.addAll(this, c);
    }

    @SuppressWarnings("unchecked")
    private static <E> boolean addAll(CopyOnWriteArrayNavigableSet<E> cowans, Collection<? extends E> c) {
        Object[] cs = c.toArray();
        if (cs.length == 0)
            return false;
        if(cs.length == 1) {
            return cowans.add((E) cs[0]);
        }
        synchronized (cowans.al.lock) {
            E[] array  = (E[]) cowans.al.getArray();
            int len = array.length;
            int added = 0;
            // uniquify and compact elements in cs
            for (int i = 0; i < cs.length; ++i) {
                Object e = Objects.requireNonNull(cs[i]);
                if (Arrays.binarySearch(array, (E) e, cowans.comparator) < 0) {
                    int at = len != 0
                            ? Arrays.binarySearch((E[]) cs, 0, added, (E) e, cowans.comparator)
                            : cowans.comparator().compare((E) e, (E) e) == 0 ? -1 : Integer.MIN_VALUE;
                    if(at < 0) {
                        // MIN_VALUE is an impossible index we use as a sentinel for bad comparator.
                        if (at == Integer.MIN_VALUE)
                            throw new IllegalArgumentException(
                            "Comparison method violates its general contract!");
                        // insertion sort it into low portion of cs.
                        at = -at - 1;
                        //System.out.println( Arrays.asList(cs) + " len:" + cs.length + " e:" + e + " at:" + at + " added:" + added);
                        System.arraycopy(cs, at, cs, at + 1, added++ - at);
                        cs[at] = e;
                    }
                }
            }
            if (added > 0) {
                Object[] newElements = (Object[]) Array.newInstance(array.getClass().getComponentType(), len + added);
                --len;
                --added;
                for(int i = newElements.length - 1; i >= 0; i--) {
                    // merge into resulting array. Both array and cs are sorted.
                    newElements[i] = len >= 0 && (added < 0 || cowans.comparator.compare(array[len], (E) cs[added]) > 0)
                        ? array[len--]
                        : cs[added--];
                }
                cowans.al.setArray(newElements);
                return true;
            }
            return false;
        }
    }

    @Override
    public Iterator<E> iterator() {
        return al.iterator();
    }

    @Override
    public int size() {
        return al.size();
    }

    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        return al.removeIf(filter);
    }

    @Override
    public void forEach(Consumer<? super E> action) {
        al.forEach(action);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return al.retainAll(c);
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        return al.removeAll(c);
    }

    @Override
    public Object[] toArray() {
        return al.toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        return al.toArray(a);
    }

    @Override
    public void clear() {
        al.clear();
    }

    /**
     * Returns a {@link Spliterator} over the elements in this set in the order
     * in which these elements were added.
     *
     * <p>The {@code Spliterator} reports {@link Spliterator#ORDERED},
     * {@link Spliterator#NONNULL}, {@link Spliterator#IMMUTABLE},
     * {@link Spliterator#DISTINCT}, and {@link Spliterator#SIZED}.
     *
     * <p>The spliterator provides a snapshot of the state of the set
     * when the spliterator was constructed. No synchronization is needed while
     * operating on the spliterator.
     *
     * @return a {@code Spliterator} over the elements in this set
     */
    @Override
    public Spliterator<E> spliterator() {
        return Spliterators.spliterator
            (al.getArray(), Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.IMMUTABLE | Spliterator.DISTINCT);
    }

    // +---+---+---+---+
    // | 2 | 4 | 6 | 8 |
    // +---+---+---+---+
    // lower(0) = null
    // lower(2) = null
    // lower(3) = 2
    // lower(8) = 6
    // lower(9) = 8

    @Override
    @SuppressWarnings("unchecked")
    public E lower(E e) {
        E[] array = (E[]) al.getArray();
        int loc = Arrays.binarySearch(array, e, comparator);
        return loc > 0
                ? array[loc - 1]
                : loc < -1 // zero or minus one means nothing strictly lower.
                    ? array[-2 - loc]
                    : null;
    }

    // +---+---+---+---+
    // | 2 | 4 | 6 | 8 |
    // +---+---+---+---+
    // floor(0) = null
    // floor(2) = 2
    // floor(3) = 2
    // floor(8) = 8
    // floor(9) = 8
    @Override
    @SuppressWarnings("unchecked")
    public E floor(E e) {
        E[] array = (E[]) al.getArray();
        int loc = Arrays.binarySearch(array, e, comparator);
        return loc >= 0
                ? array[loc]
                : loc < -1 // minus one means nothing matching or lower.
                    ? array[-2 - loc]
                    : null;
    }

    // +---+---+---+---+
    // | 2 | 4 | 6 | 8 |
    // +---+---+---+---+
    // ceiling(0) = 2
    // ceiling(2) = 2
    // ceiling(3) = 4
    // ceiling(8) = 8
    // ceiling(9) = null
    @Override
    @SuppressWarnings("unchecked")
    public E ceiling(E e) {
        E[] array = (E[]) al.getArray();
        int loc = Arrays.binarySearch(array, e, comparator);
        return loc >= 0
                ? array[loc]
                : -loc < array.length
                    ? array[-1 - loc]
                    : null;
    }

    // +---+---+---+---+
    // | 2 | 4 | 6 | 8 |
    // +---+---+---+---+
    // higher(0) = 2
    // higher(2) = 4
    // higher(3) = 4
    // higher(8) = null
    // higher(9) = null
     @Override
     @SuppressWarnings("unchecked")
     public E higher(E e) {
        E[] array = (E[]) al.getArray();
        int loc = Arrays.binarySearch(array, e, comparator);
        return loc >= 0
                ? (loc < array.length - 1 )
                    ? array[loc + 1]
                    : null
                : -loc < array.length
                    ? array[-1 - loc]
                    : null;
    }

    @Override
    public E pollFirst() {
        if(al.isEmpty()) return null;
        synchronized(al.lock) {
            if(al.isEmpty()) return null;
            E result = al.remove(0);
            return result;
        }
    }

    @Override
    public E pollLast() {
        if(al.isEmpty()) return null;
        synchronized(al.lock) {
            if(al.isEmpty()) return null;
            E result = al.remove(al.size() - 1);
            return result;
        }
    }

    @Override
    public NavigableSet<E> descendingSet() {
        return new BoundedNavigableSet<>(comparator, al, false, null, false, false, null, false, true);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Iterator<E> descendingIterator() {
        final Object[] array = al.getArray();

        return array.length == 0
                ? Collections.emptyIterator()
                : new Iterator<E>() {
            int index = array.length - 1;

            @Override
            public boolean hasNext() {
                return index >= 0;
            }

            @Override
            public E next() {
                if (hasNext()) {
                    return (E) array[index--];
                } else {
                    throw new NoSuchElementException();
                }
            }

        };
    }

    @Override
    public NavigableSet <E> subSet(E fromElement, boolean fromInclusive, E toElement, boolean toInclusive) {
        return new BoundedNavigableSet<>(comparator, al, true, fromElement, fromInclusive, true, toElement, toInclusive, false);
    }

    @Override
    public NavigableSet<E> headSet(E toElement, boolean inclusive) {
        return new BoundedNavigableSet<>(comparator, al, false, null, false, true, toElement, inclusive, false);
    }

    @Override
    public NavigableSet<E> tailSet(E fromElement, boolean inclusive) {
        return new BoundedNavigableSet<>(comparator, al, true, fromElement, inclusive, false, null, false, false);
    }

    @Override
    public SortedSet<E> subSet(E fromElement, E toElement) {
        return subSet(fromElement, true, toElement, false);
    }

    @Override
    public SortedSet<E> headSet(E toElement) {
        return headSet(toElement, false);
    }

    @Override
    public SortedSet<E> tailSet(E fromElement) {
        return tailSet(fromElement, true);
    }

    @Override
    public Comparator<? super E> comparator() {
        return comparator;
    }

    @Override
    public E first() {
        if(al.isEmpty()) throw new NoSuchElementException();
        @SuppressWarnings("unchecked")
        E[] array = (E[]) al.getArray();
        if(array.length == 0) throw new NoSuchElementException();
        return array[0];
    }

    @Override
    public E last() {
        if(al.isEmpty()) throw new NoSuchElementException();
        @SuppressWarnings("unchecked")
        E[] array = (E[]) al.getArray();
        if(array.length == 0) throw new NoSuchElementException();
        return array[array.length - 1];
    }

    private static class BoundedNavigableSet<E> extends CopyOnWriteArrayNavigableSet<E> {

        private static final long serialVersionUID = 3830104881368453055L;

        /**
         * If true then iteration is done in descending order.
         */
        final boolean descending;
        /**
         * If true then a lower bound relative to the super set.
         */
        final boolean lowerBounded;
        /**
         * If true then we have an upper bound relative to the super set.
         */
        final boolean upperBounded;
        /**
         * If true then the lower bound is included in the set.
         */
        final boolean lowerInclusive;
        /**
         * If true then the upper bound is included in the set.
         */
        final boolean upperInclusive;
        /**
         * The value of the lower bound.
         */
        final E lowerBound;
        /**
         * The value of the upper bound.
         */
        final E upperBound;

        @SuppressWarnings("unchecked")
        public BoundedNavigableSet(
            Comparator<? super E> comparator, CopyOnWriteArrayList<E> al,
            boolean lowerBounded, E fromElement, boolean lowerInclusive,
            boolean upperBounded, E toElement, boolean upperInclusive,
            boolean descending) {

            super(comparator, al);

            this.descending = descending;

            if (lowerBounded && upperBounded)  {
                int fromCompared = Integer.signum(comparator.compare(fromElement,toElement));
                int toCompared = Integer.signum(comparator.compare(toElement,fromElement));

                if(fromCompared != -toCompared) {
                    throw new IllegalArgumentException("inconsistent comparator");
                }

                if (!descending) {
                    if (fromCompared > 0) {
                        throw new IllegalArgumentException("upper < lower");
                    }
                } else {
                    if (fromCompared < 0) {
                        throw new IllegalArgumentException("upper < lower");
                    }
                }
            }

            this.lowerBounded = lowerBounded;
            this.lowerBound = fromElement;
            this.lowerInclusive = lowerInclusive;
            this.upperBounded = upperBounded;
            this.upperBound = toElement;
            this.upperInclusive = upperInclusive;
        }

        @Override
        public boolean add(E e) {
            return super.add(inBounds(e));
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean contains(Object o) {
            return checkInBounds((E) o) && super.contains(o);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Comparator<E> comparator() {
            return (Comparator<E>) (descending
                    ? comparator.reversed()
                    : comparator);
        }

        @Override
        public NavigableSet<E> descendingSet() {
            return new BoundedNavigableSet<>(
                    comparator, al,
                    upperBounded, upperBound, upperInclusive,
                    lowerBounded, lowerBound, lowerInclusive,
                    !descending);
        }

        @Override
        public NavigableSet<E> subSet(E fromElement, boolean fromInclusive, E toElement, boolean toInclusive) {
            return new BoundedNavigableSet<>(
                    comparator, al,
                    true, inBounds(fromElement), fromInclusive,
                    true, inBounds(toElement), toInclusive,
                    descending);
        }

        @Override
        public NavigableSet<E> headSet(E toElement, boolean inclusive) {
            return new BoundedNavigableSet<>(
                    comparator, al,
                    lowerBounded, lowerBound, lowerInclusive,
                    true, inBounds(toElement), inclusive,
                    descending);
        }

        @Override
        public NavigableSet<E> tailSet(E fromElement, boolean inclusive) {
            return new BoundedNavigableSet<>(
                    comparator, al,
                    true, inBounds(fromElement), inclusive,
                    upperBounded, upperBound, upperInclusive,
                    descending);
        }

        @Override
        public SortedSet<E> subSet(E fromElement, E toElement) {
            return subSet(fromElement, true, toElement, false);
        }

        @Override
        public SortedSet<E> headSet(E toElement) {
            return headSet(toElement, false);
        }

        @Override
        public SortedSet<E> tailSet(E fromElement) {
            return tailSet(fromElement, true);
        }

        private E inBounds(E element) {
            if (lowerBounded) {
                if (lowerInclusive) {
                    if (comparator.compare(lowerBound,element) > 0) {
                        throw new IllegalArgumentException("out of bounds: " + element + " < " + lowerBound);
                    }
                } else {
                    if (comparator.compare(lowerBound, element) >= 0) {
                        throw new IllegalArgumentException("out of bounds: " + element + " <= " + lowerBound);
                    }
                }
            }

            if (upperBounded) {
                if (upperInclusive) {
                    if (comparator.compare(upperBound, element) < 0) {
                        throw new IllegalArgumentException("out of bounds: " + element + " > " + upperBound);
                    }
                } else {
                    if (comparator.compare(upperBound, element) <= 0) {
                        throw new IllegalArgumentException("out of bounds: " + element + " >= " + upperBound);
                    }
                }
            }

            return element;
        }


        private boolean checkInBounds(E element) {
            if (lowerBounded) {
                if (lowerInclusive) {
                    if (comparator.compare(lowerBound,element) > 0) {
                        return false;
                    }
                } else {
                    if (comparator.compare(lowerBound, element) >= 0) {
                        return false;
                    }
                }
            }

            if (upperBounded) {
                if (upperInclusive) {
                    if (comparator.compare(upperBound, element) < 0) {
                        return false;
                    }
                } else {
                    if (comparator.compare(upperBound, element) <= 0) {
                        return false;
                    }
                }
            }

            return true;
        }


        @Override
        public Iterator<E> descendingIterator() {
            return makeIterator(!descending);
        }

        @Override
        public void forEach(Consumer<? super E> action) {
            Objects.requireNonNull(action, "action");
            @SuppressWarnings("unchecked")
            E[] array = (E[]) al.getArray();
            int start = fromLoc(array);
            int end = toLoc(array);
            for(int each=start;each<end;each++)
                action.accept(array[each]);
        }

        @Override
        public boolean removeIf(Predicate<? super E> filter) {
            Objects.requireNonNull(filter, "filter");
            synchronized(al.lock) {
                @SuppressWarnings("unchecked")
                E[] array = (E[]) al.getArray();
                int start = fromLoc(array);
                int end = toLoc(array);
                return al.subList(start, end).removeIf(filter);
            }
        }

        @Override
        public boolean retainAll(Collection<?> c) {
            synchronized(al.lock) {
                @SuppressWarnings("unchecked")
                E[] array = (E[]) al.getArray();
                int start = fromLoc(array);
                int end = toLoc(array);
                return al.subList(start, end).retainAll(c);
            }
        }

        @Override
        public boolean removeAll(Collection<?> c) {
            synchronized(al.lock) {
                @SuppressWarnings("unchecked")
                E[] array = (E[]) al.getArray();
                int start = fromLoc(array);
                int end = toLoc(array);
                return al.subList(start, end).removeAll(c);
            }
        }

        @Override
        public boolean addAll(Collection<? extends E> c) {
            for (E e : c) inBounds(e);
            return CopyOnWriteArrayNavigableSet.addAll(this, c);
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean containsAll(Collection<?> c) {
            E[] array = (E[]) al.getArray();
            int start = fromLoc(array);
            int end = toLoc(array);
            for (Object each : c) {
                if (Arrays.binarySearch(array, start, end, (E) each, comparator) < 0) {
                    return false;
        }
            }
            return true;
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean remove(Object o) {
            return checkInBounds((E) o) && super.remove(o);
        }

        @Override
        public void clear() {
            synchronized(al.lock) {
                @SuppressWarnings("unchecked")
                E[] array = (E[]) al.getArray();
                int start = fromLoc(array);
                int end = toLoc(array);
                al.removeRange(start, end);
            }
        }

        @SuppressWarnings("unchecked")
        private int fromLoc(E[] array) {
            int start;
            if(lowerBounded) {
                start = Arrays.binarySearch(array, lowerBound, descending ? comparator.reversed() : comparator);
                start = start >= 0
                        ? lowerInclusive ? start : start + 1
                        : -1 - start;
            } else {
                start = 0;
            }

            return start;
        }

        @SuppressWarnings("unchecked")
        private int toLoc(E[] array) {
            int end;
            if(upperBounded) {
                end = Arrays.binarySearch(array, upperBound, descending ? comparator.reversed() : comparator);
                end = end >= 0
                        ? upperInclusive ? end + 1 : end
                        : -1 - end;
            } else {
                end = array.length;
            }

            return end;
        }

        @Override
        public <T> T[] toArray(T[] a) {
            return makeArray(a, descending);
        }

        @SuppressWarnings("unchecked")
        public <T> T[] makeArray(T[] a, boolean inDescending) {
            E[] array = (E[]) al.getArray();
            int start = fromLoc(array);
            int end = toLoc(array);
            int len = end - start;
            if (a.length < len) {
                a = (T[]) Array.newInstance(a.getClass().getComponentType(), len);
            }
            System.arraycopy(array, start, a, 0, len);
            if(len < a.length) a[len] = null;
            if(inDescending) Collections.reverse(Arrays.asList(a).subList(0, len));
            return a;
        }

       @Override
        public Object[] toArray() {
            return makeArray(new Object[0], descending);
        }

        @Override
        public Iterator<E> iterator() {
            return makeIterator(descending);
        }

        @SuppressWarnings("unchecked")
        private Iterator<E> makeIterator(boolean inDescending) {
        List<E> asList;
            if(inDescending) {
                asList = Arrays.asList((E[]) makeArray(new Object[0], inDescending));
            } else {
                E[] array = (E[]) al.getArray();
                int start = fromLoc(array);
                int end = toLoc(array);
                asList = Arrays.asList(array).subList(start, end);
            }
            return Collections.unmodifiableList(asList).iterator();
        }

        @Override
        public int size() {
            @SuppressWarnings("unchecked")
            E[] array = (E[]) al.getArray();
            return toLoc(array) - fromLoc(array);
        }

        @Override
        @SuppressWarnings("unchecked")
        public E lower(E e) {
           E result = descending
                    ? super.higher(e)
                    : super.lower(e);
            return result != null && checkInBounds(result) ? result : null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public E floor(E e) {
            E result = descending
                    ? super.ceiling(e)
                    : super.floor(e);
            return result != null && checkInBounds(result) ? result : null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public E ceiling(E e) {
            E result = descending
                    ? super.floor(e)
                    : super.ceiling(e);
            return result != null && checkInBounds(result) ? result : null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public E higher(E e) {
            E result = descending
                    ? super.lower(e)
                    : super.higher(e);
            return result != null && checkInBounds(result) ? result : null;
        }

        @Override
        public E pollFirst() {
            return descending ? doPollLast() : doPollFirst();
        }

        private E doPollFirst() {
            if(lowerBounded)
                synchronized(al.lock) {
                    E remove = lowerInclusive ? floor(lowerBound) : higher(lowerBound);
                    if(null != remove) {
                        super.remove(remove);
                    }
                    return remove;
                }
            else
              return super.pollFirst();
        }

        @Override
        public E pollLast() {
            return descending ? doPollFirst() : doPollLast();
        }

        private E doPollLast() {
            if(upperBounded)
                synchronized(al.lock) {
                    E remove = upperInclusive ? floor(upperBound) : lower(upperBound);
                    if(null != remove) {
                        super.remove(remove);
                    }
                    return remove;
                }
            else
                return super.pollLast();
        }

        @Override
        public E first() {
            return descending ? doLast() : doFirst();
        }

        private E doFirst() {
             E result = lowerInclusive ? ceiling(lowerBound) : higher(lowerBound);
             if(null == result) {
                 throw new NoSuchElementException();
             }

             return result;
        }

        @Override
        public E last() {
            return descending ? doFirst() :  doLast();
        }

        private E doLast() {
             E result = upperInclusive ? floor(upperBound) : lower(upperBound);
             if(null == result) {
                 throw new NoSuchElementException();
             }

             return result;
        }
        @Override
        public Spliterator<E> spliterator() {
            @SuppressWarnings("unchecked")
            E[] array = (E[]) al.getArray();
            return Spliterators.spliterator(
                array, fromLoc(array), toLoc(array), Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.IMMUTABLE | Spliterator.DISTINCT);
        }
    }
}
