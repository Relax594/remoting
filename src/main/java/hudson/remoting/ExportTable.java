package hudson.remoting;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages unique ID for exported objects, and allows look-up from IDs.
 *
 * @author Kohsuke Kawaguchi
 */
final class ExportTable<T> {
    private final Map<Integer,Entry> table = new HashMap<Integer,Entry>();
    private final Map<T,Entry> reverse = new HashMap<T,Entry>();
    /**
     * {@link ExportList}s which are actively recording the current
     * export operation.
     */
    private final ThreadLocal<List<ExportList>> lists = new ThreadLocal<List<ExportList>>() {
        protected List<ExportList> initialValue() {
            return new ArrayList<ExportList>();
        }
    };

    /**
     * Information about one exporetd object.
     */
    private final class Entry {
        final int id;
        final T object;
        /**
         * Where was this object first exported?
         */
        final Exception allocationTrace;
        /**
         * Current reference count.
         * Access to {@link ExportTable} is guarded by synchronized block,
         * so accessing this field requires no further synchronization.
         */
        private int referenceCount;

        Entry(T object) {
            this.id = iota++;
            this.object = object;
            this.allocationTrace = new Exception();
            allocationTrace.fillInStackTrace();
            addRef();

            table.put(id,this);
            reverse.put(object,this);
        }

        void addRef() {
            referenceCount++;
        }

        void release() {
            if(--referenceCount==0) {
                table.remove(id);
                reverse.remove(object);
            }
        }
    }

    /**
     * Captures the list of export, so that they can be unexported later.
     *
     * This is tied to a particular thread, so it only records operations
     * on the current thread.
     */
    public final class ExportList extends ArrayList<Entry> {
        void release() {
            synchronized(ExportTable.this) {
                for (Entry e : this)
                    e.release();
            }
        }
        void stopRecording() {
            synchronized(ExportTable.this) {
                lists.get().remove(this);
            }
        }
    }

    /**
     * Unique ID generator.
     */
    private int iota = 1;

    /**
     * Starts the recording of the export operations
     * and returns the list that captures the result.
     *
     * @see ExportList#stopRecording()
     */
    public synchronized ExportList startRecording() {
        ExportList el = new ExportList();
        lists.get().add(el);
        return el;
    }

    /**
     * Exports the given object.
     *
     * <p>
     * Until the object is {@link #unexport(Object) unexported}, it will
     * not be subject to GC.
     *
     * @return
     *      The assigned 'object ID'. If the object is already exported,
     *      it will return the ID already assigned to it.
     */
    public synchronized int export(T t) {
        return export(t,true);
    }

    /**
     * @param notifyListener
     *      If false, listener will not be notified. This is used to
     *      create an export that won't get unexported when the call returns.
     */
    public synchronized int export(T t, boolean notifyListener) {
        if(t==null)    return 0;   // bootstrap classloader

        Entry e = reverse.get(t);
        if(e==null)
            e = new Entry(t);
        else
            e.addRef();

        if(notifyListener)
            for (ExportList list : lists.get())
                list.add(e);

        return e.id;
    }

    public synchronized T get(int id) {
        Entry e = table.get(id);
        if(e!=null) return e.object;
        else        return null;
    }

    /**
     * Removes the exported object from the table.
     */
    public synchronized void unexport(T t) {
        if(t==null)     return;
        Entry e = reverse.get(t);
        if(e==null)    return; // presumably already unexported
        e.release();
    }

    /**
     * Dumps the contents of the table to a file.
     */
    public synchronized void dump(PrintWriter w) throws IOException {
        for (Entry e : table.values()) {
            w.printf("#%d (ref.%d) : %s\n", e.id, e.referenceCount, e.object);
            e.allocationTrace.printStackTrace(w);
        }
    }
}
