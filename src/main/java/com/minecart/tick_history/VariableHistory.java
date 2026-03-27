package com.minecart.tick_history;

import com.minecart.math.function.DoubleVar;
import org.apache.commons.lang3.mutable.MutableBoolean;

public class VariableHistory {

    private final DoubleVar target;
    private double[] buffer;
    private int head = 0;
    private boolean isFull = false;

    /**
     * @param target The variable to observe
     * @param ticksToStore How many ticks of history to keep (e.g., 100 ticks = 5 seconds)
     */
    public VariableHistory(DoubleVar target, int ticksToStore) {
        this.target = target;
        this.buffer = new double[ticksToStore];
    }

    /**
     * Call this exactly once per server tick, AFTER system.solve() has finished.
     */
    public void tick() {
        buffer[head] = target.getValue();

        head++;
        if (head >= buffer.length) {
            head = 0;
            isFull = true;
        }
    }

    /**
     * Retrieves the history from oldest to newest.
     */
    public double[] getOrderedHistory() {
        int capacity = buffer.length;
        int currentSize = isFull ? capacity : head;
        double[] ordered = new double[currentSize];

        if (!isFull) {
            System.arraycopy(buffer, 0, ordered, 0, head);
        } else {
            int tailLength = capacity - head;
            System.arraycopy(buffer, head, ordered, 0, tailLength);
            System.arraycopy(buffer, 0, ordered, tailLength, head);
        }
        return ordered;
    }

    /**
     * Retrieves the recorded value from a specific number of ticks in the past.
     * @param ticksAgo 0 is the most recently recorded tick, 1 is the tick before that, etc.
     * @param success Mutable flag that will be set to false if the requested tick is out of bounds.
     * @return The recorded double value, or 0.0 if out of bounds.
     */
    public double getValueTicksAgo(int ticksAgo) {
        int capacity = buffer.length;
        int currentSize = isFull ? capacity : head;

        // Return NaN to signal "Out of Bounds" or "No Data"
        if (ticksAgo < 0 || ticksAgo >= currentSize) {
            return Double.NaN;
        }

        int index = (((head - 1 - ticksAgo) % capacity) + capacity) % capacity;
        return buffer[index];
    }

    /**
     * Dynamically changes the capacity of the history buffer.
     * Preserves existing data. If shrinking, the oldest data is discarded.
     * @param newCapacity The new number of ticks to store.
     */
    public void resize(int newCapacity) {
        if (newCapacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than 0");
        }
        if (newCapacity == buffer.length) return;

        double[] currentHistory = getOrderedHistory();
        double[] newBuffer = new double[newCapacity];

        int elementsToCopy = Math.min(newCapacity, currentHistory.length);
        int startIndex = currentHistory.length - elementsToCopy;

        System.arraycopy(currentHistory, startIndex, newBuffer, 0, elementsToCopy);

        this.buffer = newBuffer;

        if (elementsToCopy == newCapacity) {
            this.head = 0;
            this.isFull = true;
        } else {
            this.head = elementsToCopy;
            this.isFull = false;
        }
    }

    public void clear() {
        head = 0;
        isFull = false;
    }
}