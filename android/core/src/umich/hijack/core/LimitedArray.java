package umich.hijack.core;

public class LimitedArray {
	private final int maxSize;// max array size
	private int p = 0;  // pointer
	private int size = 0;  // size of array
	private final int[] values;    // actual array
	private int total;
	private double avg; // average

	public LimitedArray (int set_size) {
		maxSize = set_size;
		p = 0;
		size = 0;
		values = new int[maxSize];
		total = 0;
		avg = 0.0;
	}

	public void insert (int e) {
		if (size < maxSize) {
			size++;
		}

		// Remove the old value from the total
		total -= values[p];

		// Recalculate metrics with the new value
		total += e;
		avg = (double)total / (double) size;

		// Actually insert the new value
		values[p] = e;
		p = (p + 1) % maxSize;
	}

	public int length () {
		return size;
	}

	public double average () {
		return avg;
	}

	public double variance () {
		long varianceSum = 0;
		for (int i=0; i<size; i++) {
			varianceSum += (long) (values[i]-avg) * (long) (values[i]-avg);
		}
		return (double) varianceSum / (double) size;
	}
}
