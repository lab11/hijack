package umich.hijack.core;

public class LimitedArray {
	private int maxSize;// max array size
	private int p = 0;  // pointer
	private int size = 0;  // size of array
	private int[] values;    // actual array
	private int[] varianceParts;
	private int total;
	private int varTimesSize;
	private int avg; // average
	private int var; // variance

	public LimitedArray (int set_size) {
		maxSize = set_size;
		p = 0;
		size = 0;
		values = new int[maxSize];
		varianceParts = new int[maxSize];
		total = 0;
		varTimesSize = 0;
		avg = 0;
		var = 0;
	}

	public void insert (int e) {
		if (size < maxSize) {
			size++;
		}
		
		// Remove the old value from the total
		total -= values[p];
		varTimesSize -= varianceParts[p];
		
		// Recalculate metrics with the new value
		total += e;
		avg = total/size;
		varTimesSize += (avg-e)*(avg-e);
		var = varTimesSize/size;
		
		// Actually insert the new value
		values[p] = e;
		p = (p + 1) % maxSize;
	}
	
	public int length () {
		return size;
	}
	
	public int average () {
		return avg;
	}
	
	public int variance () {
		return var;
	}
}
