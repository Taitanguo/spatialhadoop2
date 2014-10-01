/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the
 * NOTICE file distributed with this work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package edu.umn.cs.spatialHadoop.operations;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.ArrayWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.ClusterStatus;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.LocalJobRunner;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.RunningJob;
import org.apache.hadoop.util.GenericOptionsParser;

import edu.umn.cs.spatialHadoop.ImageOutputFormat;
import edu.umn.cs.spatialHadoop.ImageWritable;
import edu.umn.cs.spatialHadoop.OperationsParams;
import edu.umn.cs.spatialHadoop.core.CellInfo;
import edu.umn.cs.spatialHadoop.core.GlobalIndex;
import edu.umn.cs.spatialHadoop.core.GridInfo;
import edu.umn.cs.spatialHadoop.core.Partition;
import edu.umn.cs.spatialHadoop.core.Point;
import edu.umn.cs.spatialHadoop.core.Rectangle;
import edu.umn.cs.spatialHadoop.core.Shape;
import edu.umn.cs.spatialHadoop.core.SpatialSite;
import edu.umn.cs.spatialHadoop.mapred.BlockFilter;
import edu.umn.cs.spatialHadoop.mapred.ShapeArrayInputFormat;
import edu.umn.cs.spatialHadoop.mapred.ShapeInputFormat;
import edu.umn.cs.spatialHadoop.mapred.TextOutputFormat;
import edu.umn.cs.spatialHadoop.nasa.NASAPoint;
import edu.umn.cs.spatialHadoop.nasa.NASARectangle;
import edu.umn.cs.spatialHadoop.operations.Aggregate.MinMax;
import edu.umn.cs.spatialHadoop.operations.GeometricPlot.PlotOutputCommitter;
import edu.umn.cs.spatialHadoop.operations.RangeQuery.RangeFilter;

/**
 * Draws an image of all shapes in an input file.
 * @author Ahmed Eldawy
 *
 */
public class HeatMapPlot {
  /**Logger*/
  private static final Log LOG = LogFactory.getLog(HeatMapPlot.class);
  
  /**
   * Stores the frequencies in a two-dimensional matrix and produce an image out of it.
   * @author Eldawy
   *
   */
  public static class FrequencyMap implements Writable {
    float[][] frequency;
    float[][] gaussianKernel;
    int radius;
    
    private Map<Integer, BufferedImage> cachedCircles = new HashMap<Integer, BufferedImage>();
    public FrequencyMap() {
    }
    
    public FrequencyMap(int width, int height) {
      frequency = new float[width][height];
    }
    
    public FrequencyMap(FrequencyMap other) {
      this.frequency = new float[other.getWidth()][other.getHeight()];
      for (int x = 0; x < this.getWidth(); x++)
        for (int y = 0; y < this.getHeight(); y++) {
          this.frequency[x][y] = other.frequency[x][y];
        }
    }

    public void combine(FrequencyMap other) {
      if (other.getWidth() != this.getWidth() ||
          other.getHeight() != this.getHeight())
        throw new RuntimeException("Incompatible frequency map sizes "+this+", "+other);
      for (int x = 0; x < this.getWidth(); x++)
        for (int y = 0; y < this.getHeight(); y++) {
          this.frequency[x][y] += other.frequency[x][y];
        }
    }

    @Override
    public void write(DataOutput out) throws IOException {
      out.writeInt(this.getWidth());
      out.writeInt(this.getHeight());
      for (float[] col : frequency) {
        int y1 = 0;
        while (y1 < col.length) {
          int y2 = y1;
          while (y2 < col.length && col[y2] == col[y1])
            y2++;
          if (y2 - y1 == 1) {
            out.writeFloat(-col[y1]);
          } else {
            out.writeFloat(y2 - y1);
            out.writeFloat(col[y1]);
          }
          y1 = y2;
        }
      }
    }
    
    @Override
    public void readFields(DataInput in) throws IOException {
      int width = in.readInt();
      int height = in.readInt();
      if (getWidth() != width || getHeight() != height)
        frequency = new float[width][height];
      for (int x = 0; x < width; x++) {
        int y = 0;
        while (y < height) {
          float v = in.readFloat();
          if (v <= 0) {
            frequency[x][y] = -v;
            y++;
          } else {
            float cnt = v;
            v = in.readFloat();
            float y2 = y + cnt;
            while (y < y2) {
              frequency[x][y] = v;
              y++;
            }
          }
        }
      }
    }
    
    @Override
    protected FrequencyMap clone() {
      return new FrequencyMap(this);
    }
    
    @Override
    public boolean equals(Object obj) {
      FrequencyMap other = (FrequencyMap) obj;
      if (this.getWidth() != other.getWidth())
        return false;
      if (this.getHeight() != other.getHeight())
        return false;
      for (int x = 0; x < this.getWidth(); x++) {
        for (int y = 0; y < this.getHeight(); y++) {
          if (this.frequency[x][y] != other.frequency[x][y])
            return false;
        }
      }
      return true;
    }
    
    @Override
    public String toString() {
      return "Frequency Map:"+this.getWidth()+"x"+this.getHeight();
    }
    
    private MinMax getValueRange() {
      Map<Float, Integer> histogram = new HashMap<Float, Integer>();
      MinMax minMax = new MinMax(Integer.MAX_VALUE, Integer.MIN_VALUE);
      for (float[] col : frequency)
        for (float value : col) {
          if (!histogram.containsKey(value)) {
            histogram.put(value, 1);
          } else {
            histogram.put(value, histogram.get(value) + 1);
          }
          minMax.expand(value);
        }
      return minMax;
    }

    private int getWidth() {
      return frequency == null? 0 : frequency.length;
    }

    private int getHeight() {
      return frequency == null? 0 : frequency[0].length;
    }

    public BufferedImage toImage(MinMax valueRange, boolean skipZeros, int radius) {
      this.radius = radius;
      if (valueRange == null)
        valueRange = getValueRange();
      LOG.info("Using the value range: "+valueRange);
      NASAPoint.minValue = valueRange.minValue;
      NASAPoint.maxValue = valueRange.maxValue;
      BufferedImage image = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_ARGB);
      for (int x = 0; x < this.getWidth(); x++)
        for (int y = 0; y < this.getHeight(); y++) {
          if (!skipZeros || frequency[x][y] > valueRange.minValue) {
            Color color = NASARectangle.calculateColor(frequency[x][y]);
            image.setRGB(x, y, color.getRGB());
          }
        }
      return image;
    }

    public void addPoint(int cx, int cy, int radius) {
      BufferedImage circle = getCircle(radius);
      for (int x = 0; x < circle.getWidth(); x++) {
        for (int y = 0; y < circle.getHeight(); y++) {
          int imgx = x - radius + cx;
          int imgy = y - radius + cy;
          if (imgx >= 0 && imgx < getWidth() && imgy >= 0 && imgy < getHeight()) {
            boolean filled = (circle.getRGB(x, y) & 0xff) == 0;
            if (filled) {
              frequency[x - radius + cx][y - radius + cy]++;
            }
          }
        }
      }
    }
    
    // Add a point but updating the distribution in the radius using Gaussian Distribution.
    public void addGaussianPoint(int cx, int cy, int radius) {
    	BufferedImage circle = getCircle(radius);
    	// Create Gaussian Kernel for the first time.
    	if(gaussianKernel == null) {
        	createGaussianKernel(radius);
    	}
    	
    	for (int x = 0; x < circle.getWidth(); x++) {
            for (int y = 0; y < circle.getHeight(); y++) {
              int imgx = x - radius + cx;
              int imgy = y - radius + cy;
              if (imgx >= 0 && imgx < getWidth() && imgy >= 0 && imgy < getHeight()) {
                  frequency[x - radius + cx][y - radius + cy] += gaussianKernel[x][y];
              }
            }
        }
    }
    
    // Create the GaussianKernel from a radius and standard deviation.
    public void createGaussianKernel(int radius) {
    	float stdev = 8;
    	gaussianKernel = new float[2*radius+1][2*radius+1];
    	float temp = 0;
    	
    	// Create the gaussianKernel.
    	for (int j = 0; j < gaussianKernel.length; j++) {
			for (int i = 0; i < gaussianKernel.length; i++) {
				// If this is the center of the kernel.
				temp = (float) Math.pow(Math.E, (-1/(2*Math.pow(stdev,2))) * 
						(Math.pow(Math.abs(radius - i), 2) + Math.pow(Math.abs(radius - j), 2)));
				gaussianKernel[i][j] = temp;
			}
		}
    }

    public BufferedImage getCircle(int radius) {
      BufferedImage circle = cachedCircles.get(radius);
      if (circle == null) {
        circle = new BufferedImage(radius * 2, radius * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = circle.createGraphics();
        graphics.setBackground(Color.WHITE);
        graphics.clearRect(0, 0, radius * 2, radius * 2);
        graphics.setColor(Color.BLACK);
        graphics.fillArc(0, 0, radius * 2, radius * 2, 0, 360);
        graphics.dispose();
        cachedCircles.put(radius, circle);
      }
      return circle;
    }
  }

  /**
   * If the processed block is already partitioned (via global index), then
   * the output is the same as input (Identity map function). If the input
   * partition is not partitioned (a heap file), then the given shape is output
   * to all overlapping partitions.
   * @author Ahmed Eldawy
   *
   */
  public static class DataPartitionMap extends MapReduceBase 
    implements Mapper<Rectangle, ArrayWritable, NullWritable, FrequencyMap> {

    /**Only objects inside this query range are drawn*/
    private Shape queryRange;
    private int imageWidth;
    private int imageHeight;
    private Rectangle drawMbr;
    /**Used to output values*/
    private FrequencyMap frequencyMap;
    /**Radius to use for smoothing the heat map*/
    private int radius;
    private boolean adaptiveSample;
    private float adaptiveSampleRatio;
    /**Use smoothing or not*/
    private boolean smooth;
    
    @Override
    public void configure(JobConf job) {
      System.setProperty("java.awt.headless", "true");
      super.configure(job);
      this.queryRange = OperationsParams.getShape(job, "rect");
      this.drawMbr = queryRange != null ? queryRange.getMBR() : ImageOutputFormat.getFileMBR(job);
      this.imageWidth = job.getInt("width", 1000);
      this.imageHeight = job.getInt("height", 1000);
      this.radius = job.getInt("radius", 5);
      frequencyMap = new FrequencyMap(imageWidth, imageHeight);
      this.adaptiveSample = job.getBoolean("sample", false);
      if (this.adaptiveSample) {
        // Calculate the sample ratio
        this.adaptiveSampleRatio = job.getFloat(GeometricPlot.AdaptiveSampleRatio, 0.01f);
      }
      this.smooth = job.getBoolean("smooth", false);
    }

    @Override
    public void map(Rectangle dummy, ArrayWritable shapesAr,
        OutputCollector<NullWritable, FrequencyMap> output, Reporter reporter)
        throws IOException {
      for (Writable w : shapesAr.get()) {
        Shape s = (Shape) w;
        if (adaptiveSample && s instanceof Point
            && Math.random() > adaptiveSampleRatio)
            return;
        Point center;
        if (s instanceof Point) {
          center = (Point) s;
        } else if (s instanceof Rectangle) {
          center = ((Rectangle) s).getCenterPoint();
        } else {
          Rectangle shapeMBR = s.getMBR();
          if (shapeMBR == null)
            continue;
          center = shapeMBR.getCenterPoint();
        }
        int centerx = (int) Math.round((center.x - drawMbr.x1) * imageWidth / drawMbr.getWidth());
        int centery = (int) Math.round((center.y - drawMbr.y1) * imageHeight / drawMbr.getHeight());
        if(smooth) {
        	frequencyMap.addGaussianPoint(centerx, centery, radius);
        } else {
            frequencyMap.addPoint(centerx, centery, radius);
        }
      }
      
      output.collect(NullWritable.get(), frequencyMap);
    }
    
  }

  public static class DataPartitionReduce extends MapReduceBase
      implements Reducer<NullWritable, FrequencyMap, Rectangle, ImageWritable> {
    
    private Rectangle drawMBR;
    /**Range of values to do the gradient of the heat map*/
    private MinMax valueRange;
    private boolean skipZeros;
    private int radius;

    @Override
    public void configure(JobConf job) {
      this.radius = job.getInt("radius", 5);
      System.setProperty("java.awt.headless", "true");
      super.configure(job);
      Shape queryRange = OperationsParams.getShape(job, "rect");
      this.drawMBR = queryRange != null ? queryRange.getMBR() : ImageOutputFormat.getFileMBR(job);
      NASAPoint.setColor1(OperationsParams.getColor(job, "color1", Color.BLUE));
      NASAPoint.setColor2(OperationsParams.getColor(job, "color2", Color.RED));
      NASAPoint.gradientType = OperationsParams.getGradientType(job, "gradient", NASAPoint.GradientType.GT_HUE);
      this.skipZeros = job.getBoolean("skipzeros", false);
      String valueRangeStr = job.get("valuerange");
      if (valueRangeStr != null) {
        String[] parts = valueRangeStr.contains("..") ? valueRangeStr.split("\\.\\.", 2) : valueRangeStr.split(",", 2);
        this.valueRange = new MinMax(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
      }
    }

    @Override
    public void reduce(NullWritable dummy, Iterator<FrequencyMap> frequencies,
        OutputCollector<Rectangle, ImageWritable> output, Reporter reporter)
        throws IOException {
      if (!frequencies.hasNext())
        return;
      FrequencyMap combined = frequencies.next().clone();
      while (frequencies.hasNext())
        combined.combine(frequencies.next());

      BufferedImage image = combined.toImage(valueRange, skipZeros, radius);
      output.collect(drawMBR, new ImageWritable(image));
    }
  }


  /**The grid used to partition data across reducers*/
  private static final String PartitionGrid = "PlotHeatMap.PartitionGrid";
  
  /**
   * The map function for the plot heat map operation that uses spatial
   * partitioning.
   * @author Eldawy
   *
   */
  public static class GridPartitionMap extends MapReduceBase 
  implements Mapper<Rectangle, ArrayWritable, IntWritable, Point> {
    
    /**The grid used to partition the space*/
    private GridInfo partitionGrid;
    /**Shared output key*/
    private IntWritable cellNumber;
    /**The range to plot*/
    private Shape queryRange;
    /**Radius in data space around each point*/
    private float radiusX, radiusY;
    private boolean adaptiveSample;
    private float adaptiveSampleRatio;
    
    @Override
    public void configure(JobConf job) {
      super.configure(job);
      this.partitionGrid = (GridInfo) OperationsParams.getShape(job, PartitionGrid);
      this.cellNumber = new IntWritable();
      this.queryRange = OperationsParams.getShape(job, "rect");
      
      int imageWidth = job.getInt("width", 1000);
      int imageHeight = job.getInt("height", 1000);
      int radiusPx = job.getInt("radius", 5);
      this.radiusX = (float) (radiusPx * partitionGrid.getWidth() / imageWidth);
      this.radiusY = (float) (radiusPx * partitionGrid.getHeight() / imageHeight);
      if (this.adaptiveSample) {
        // Calculate the sample ratio
        this.adaptiveSampleRatio = job.getFloat(GeometricPlot.AdaptiveSampleRatio, 0.01f);
      }
    }
    
    @Override
    public void map(Rectangle dummy, ArrayWritable value,
        OutputCollector<IntWritable, Point> output, Reporter reporter)
        throws IOException {
    	
      for(Shape s : (Shape[]) value.get()) {
    	  if (adaptiveSample && s instanceof Point
    			  && Math.random() > adaptiveSampleRatio)
    		  continue;

    	  Point center;
    	  if (s instanceof Point) {
    		  center = new Point((Point)s);
    	  } else if (s instanceof Rectangle) {
    		  center = ((Rectangle) s).getCenterPoint();
    	  } else {
    		  Rectangle shapeMBR = s.getMBR();
    		  if (shapeMBR == null)
    			  continue;
    		  center = shapeMBR.getCenterPoint();
    	  }
    	  Rectangle shapeMBR = center.getMBR().buffer(radiusX, radiusY);
    	  // Skip shapes outside query range if query range is set
    	  if (queryRange != null && !shapeMBR.isIntersected(queryRange))
    		  continue;
    	  // Replicate to all overlapping cells
    	  java.awt.Rectangle overlappingCells = partitionGrid.getOverlappingCells(shapeMBR);
    	  for (int i = 0; i < overlappingCells.width; i++) {
    		  int x = overlappingCells.x + i;
    		  for (int j = 0; j < overlappingCells.height; j++) {
    			  int y = overlappingCells.y + j;
    			  cellNumber.set(y * partitionGrid.columns + x + 1);
    			  output.collect(cellNumber, center);
    		  }
    	  }
      }
    }
  }

  /**
   * The reduce function of the plot heat map operations that uses
   * spatial partitioning.
   * @author Eldawy
   *
   */
  public static class GridPartitionReduce extends MapReduceBase
    implements Reducer<IntWritable, Point, Rectangle, ImageWritable> {
    private GridInfo partitionGrid;
    private int imageWidth, imageHeight;
    private int radius;
    private boolean skipZeros;
    private MinMax valueRange;
    private boolean smooth;
    
    @Override
    public void configure(JobConf job) {
      super.configure(job);
      this.partitionGrid = (GridInfo) OperationsParams.getShape(job, PartitionGrid);
      this.imageWidth = job.getInt("width", 1000);
      this.imageHeight = job.getInt("height", 1000);
      this.radius = job.getInt("radius", 5);
      NASAPoint.setColor1(OperationsParams.getColor(job, "color1", Color.BLUE));
      NASAPoint.setColor2(OperationsParams.getColor(job, "color2", Color.RED));
      NASAPoint.gradientType = OperationsParams.getGradientType(job, "gradient", NASAPoint.GradientType.GT_HUE);
      this.skipZeros = job.getBoolean("skipzeros", false);
      String valueRangeStr = job.get("valuerange");
      if (valueRangeStr != null) {
        String[] parts = valueRangeStr.contains("..") ? valueRangeStr.split("\\.\\.", 2) : valueRangeStr.split(",", 2);
        this.valueRange = new MinMax(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
      }
      this.smooth = job.getBoolean("smooth", false);
    }

    @Override
    public void reduce(IntWritable cellNumber, Iterator<Point> points,
        OutputCollector<Rectangle, ImageWritable> output, Reporter reporter)
        throws IOException {
      CellInfo cellInfo = partitionGrid.getCell(cellNumber.get());
      // Initialize the image
      int image_x1 = (int) Math.floor((cellInfo.x1 - partitionGrid.x1) * imageWidth / partitionGrid.getWidth());
      int image_y1 = (int) Math.floor((cellInfo.y1 - partitionGrid.y1) * imageHeight / partitionGrid.getHeight());
      int image_x2 = (int) Math.ceil((cellInfo.x2 - partitionGrid.x1) * imageWidth / partitionGrid.getWidth());
      int image_y2 = (int) Math.ceil((cellInfo.y2 - partitionGrid.y1) * imageHeight / partitionGrid.getHeight());
      int tile_width = image_x2 - image_x1;
      int tile_height = image_y2 - image_y1;
      FrequencyMap frequencyMap = new FrequencyMap(tile_width, tile_height);
      while (points.hasNext()) {
        Point p = points.next();
        int centerx = (int) Math.round((p.x - cellInfo.x1) * tile_width / cellInfo.getWidth());
        int centery = (int) Math.round((p.y - cellInfo.y1) * tile_height / cellInfo.getHeight());
        if(smooth) {
        	frequencyMap.addGaussianPoint(centerx, centery, radius);
        } else {
            frequencyMap.addPoint(centerx, centery, radius);
        }
      }
      BufferedImage image = frequencyMap.toImage(valueRange, skipZeros, radius);
      output.collect(cellInfo, new ImageWritable(image));
    }
  }
  
  /**
   * The map function for the plot heat map operation that uses spatial
   * partitioning.
   * @author Eldawy
   *
   */
  public static class SkewedPartitionMap extends MapReduceBase 
    implements Mapper<Rectangle, ArrayWritable, IntWritable, Point> {
    
    /**The cells used to partition the space*/
    private CellInfo[] cells;
    /**Shared output key*/
    private IntWritable cellNumber = new IntWritable();
    /**The range to plot*/
    private Shape queryRange;
    /**Radius in data space around each point*/
    private float radiusX, radiusY;
    private boolean adaptiveSample;
    private float adaptiveSampleRatio;
    
    @Override
    public void configure(JobConf job) {
      super.configure(job);
      try {
        this.cells = SpatialSite.getCells(job);
      } catch (IOException e) {
        throw new RuntimeException("Cannot get cells for the job", e);
      }
      this.queryRange = OperationsParams.getShape(job, "rect");
      
      int imageWidth = job.getInt("width", 1000);
      int imageHeight = job.getInt("height", 1000);
      int radiusPx = job.getInt("radius", 5);
      
      Rectangle fileMBR = new Rectangle(Double.MAX_VALUE, Double.MAX_VALUE,
          -Double.MAX_VALUE, -Double.MAX_VALUE);
      for (CellInfo cell : cells) {
        fileMBR.expand(cell);
      }
      this.radiusX = (float) (radiusPx * fileMBR.getWidth() / imageWidth);
      this.radiusY = (float) (radiusPx * fileMBR.getHeight() / imageHeight);
      if (this.adaptiveSample) {
        // Calculate the sample ratio
        this.adaptiveSampleRatio = job.getFloat(GeometricPlot.AdaptiveSampleRatio, 0.01f);
      }
    }
    
    @Override
    public void map(Rectangle dummy, ArrayWritable value,
        OutputCollector<IntWritable, Point> output, Reporter reporter)
        throws IOException {
    	
      for(Shape s : (Shape[]) value.get()) {
    	  if (adaptiveSample && s instanceof Point
    			  && Math.random() > adaptiveSampleRatio)
    		  continue;

    	  Point center;
    	  if (s instanceof Point) {
    		  center = new Point((Point)s);
    	  } else if (s instanceof Rectangle) {
    		  center = ((Rectangle) s).getCenterPoint();
    	  } else {
    		  Rectangle shapeMBR = s.getMBR();
    		  if (shapeMBR == null)
    			  continue;
    		  center = shapeMBR.getCenterPoint();
    	  }
    	  Rectangle shapeMBR = center.getMBR().buffer(radiusX, radiusY);
    	  // Skip shapes outside query range if query range is set
    	  if (queryRange != null && !shapeMBR.isIntersected(queryRange))
    		  continue;
    	  for (CellInfo cell : cells) {
    		  if (cell.isIntersected(shapeMBR)) {
    			  cellNumber.set(cell.cellId);
    			  output.collect(cellNumber, center);
    		  }
    	  }
      }
    }
  }

  public static class SkewedPartitionReduce extends MapReduceBase
  implements Reducer<IntWritable, Point, Rectangle, ImageWritable> {
    private CellInfo[] cells;
    private int imageWidth, imageHeight;
    private int radius;
    private boolean skipZeros;
    private MinMax valueRange;
    private Rectangle fileMBR;
    private boolean smooth;

    @Override
    public void configure(JobConf job) {
      super.configure(job);
      try {
        this.cells = SpatialSite.getCells(job);
      } catch (IOException e) {
        throw new RuntimeException("Cannot get cells for the job", e);
      }
      this.imageWidth = job.getInt("width", 1000);
      this.imageHeight = job.getInt("height", 1000);
      this.radius = job.getInt("radius", 5);
      NASAPoint.setColor1(OperationsParams.getColor(job, "color1", Color.BLUE));
      NASAPoint.setColor2(OperationsParams.getColor(job, "color2", Color.RED));
      NASAPoint.gradientType = OperationsParams.getGradientType(job, "gradient", NASAPoint.GradientType.GT_HUE);
      this.skipZeros = job.getBoolean("skipzeros", false);
      String valueRangeStr = job.get("valuerange");
      if (valueRangeStr != null) {
        String[] parts = valueRangeStr.contains("..") ? valueRangeStr.split("\\.\\.", 2) : valueRangeStr.split(",", 2);
        this.valueRange = new MinMax(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
      }
      this.fileMBR = new Rectangle(Double.MAX_VALUE, Double.MAX_VALUE,
          -Double.MAX_VALUE, -Double.MAX_VALUE);
      for (CellInfo cell : cells) {
        fileMBR.expand(cell);
      }
      this.smooth = job.getBoolean("smooth", false);
    }

    @Override
    public void reduce(IntWritable cellNumber, Iterator<Point> points,
        OutputCollector<Rectangle, ImageWritable> output, Reporter reporter)
            throws IOException {
      CellInfo cellInfo = null;
      int iCell = 0;
      while (iCell < cells.length && cells[iCell].cellId != cellNumber.get())
        iCell++;
      if (iCell >= cells.length)
        throw new RuntimeException("Cannot find cell: "+cellNumber);
      cellInfo = cells[iCell];

      // Initialize the image
      int image_x1 = (int) Math.floor((cellInfo.x1 - fileMBR.x1) * imageWidth / fileMBR.getWidth());
      int image_y1 = (int) Math.floor((cellInfo.y1 - fileMBR.y1) * imageHeight / fileMBR.getHeight());
      int image_x2 = (int) Math.ceil((cellInfo.x2 - fileMBR.x1) * imageWidth / fileMBR.getWidth());
      int image_y2 = (int) Math.ceil((cellInfo.y2 - fileMBR.y1) * imageHeight / fileMBR.getHeight());
      int tile_width = image_x2 - image_x1;
      int tile_height = image_y2 - image_y1;
      FrequencyMap frequencyMap = new FrequencyMap(tile_width, tile_height);
      while (points.hasNext()) {
        Point p = points.next();
        int centerx = (int) Math.round((p.x - cellInfo.x1) * tile_width / cellInfo.getWidth());
        int centery = (int) Math.round((p.y - cellInfo.y1) * tile_height / cellInfo.getHeight());
        if(smooth) {
        	frequencyMap.addGaussianPoint(centerx, centery, radius);
        } else {
            frequencyMap.addPoint(centerx, centery, radius);
        }
      }
      BufferedImage image = frequencyMap.toImage(valueRange, skipZeros, radius);
      output.collect(cellInfo, new ImageWritable(image));
    }
}

  
  /**Last submitted Plot job*/
  public static RunningJob lastSubmittedJob;
  
  private static RunningJob plotHeatMapMapReduce(Path inFile, Path outFile,
      OperationsParams params) throws IOException {
    boolean background = params.is("background");

    int width = params.getInt("width", 1000);
    int height = params.getInt("height", 1000);

    Shape plotRange = params.getShape("rect", null);

    boolean keepAspectRatio = params.is("keep-ratio", true);

    JobConf job = new JobConf(params, HeatMapPlot.class);
    job.setJobName("Plot HeatMap");
    
    Rectangle fileMBR;
    // Run MBR operation in synchronous mode
    OperationsParams mbrArgs = new OperationsParams(params);
    mbrArgs.setBoolean("background", false);
    fileMBR = plotRange != null ? plotRange.getMBR() :
      FileMBR.fileMBR(inFile, mbrArgs);
    LOG.info("File MBR: "+fileMBR);

    String partition = job.get("partition", "data").toLowerCase();
    ClusterStatus clusterStatus = new JobClient(job).getClusterStatus();
    if (partition.equals("data")) {
      LOG.info("Plot using data partitioning");
      job.setMapperClass(DataPartitionMap.class);
      // A combiner is not useful here because each mapper outputs one record
      job.setReducerClass(DataPartitionReduce.class);
      job.setMapOutputKeyClass(NullWritable.class);
      job.setMapOutputValueClass(FrequencyMap.class);
      job.setInputFormat(ShapeArrayInputFormat.class);
    } else if (partition.equals("space") || partition.equals("grid")) {
      FileSystem inFs = inFile.getFileSystem(job);
      GlobalIndex<Partition> gIndex = SpatialSite.getGlobalIndex(inFs, inFile);
      job.setInputFormat(ShapeArrayInputFormat.class);
      job.setMapOutputKeyClass(IntWritable.class);
      job.setMapOutputValueClass(Point.class);
      if (gIndex == null || gIndex.size() == 1) {
        // Input file is not indexed. Use grid or skewed partitioning
        // A special case of a global index of one cell indicates
        // a non-indexed file with a cached MBR
        if (partition.equals("grid")) {
          LOG.info("Grid partition a file then plot");
          job.setMapperClass(GridPartitionMap.class);
          job.setReducerClass(GridPartitionReduce.class);
          
          GridInfo partitionGrid = new GridInfo(fileMBR.x1, fileMBR.y1, fileMBR.x2,
              fileMBR.y2);
          partitionGrid.calculateCellDimensions(
              (int) Math.max(1, clusterStatus.getMaxReduceTasks()));
          OperationsParams.setShape(job, PartitionGrid, partitionGrid);
        } else {
          LOG.info("Use skewed partitioning then plot");
          job.setMapperClass(SkewedPartitionMap.class);
          job.setReducerClass(SkewedPartitionReduce.class);
          // Pack in rectangles using an RTree
          CellInfo[] cellInfos = Repartition.packInRectangles(inFile, outFile, params, fileMBR);
          SpatialSite.setCells(job, cellInfos);
          job.setInputFormat(ShapeArrayInputFormat.class);
        }
      } else {
        LOG.info("Partitioned plot with an already partitioned file");
        // Input file is already partitioned. Use the same partitioning
        job.setMapperClass(SkewedPartitionMap.class);
        job.setReducerClass(SkewedPartitionReduce.class);
        SpatialSite.setCells(job, SpatialSite.cellsOf(gIndex));
      }
    } else {
      throw new RuntimeException("Unknown partition scheme '"+job.get("partition")+"'");
    }
    job.setNumMapTasks(clusterStatus.getMaxMapTasks() * 5);
    job.setNumReduceTasks(Math.max(1, clusterStatus.getMaxReduceTasks()));

    if (keepAspectRatio) {
      // Adjust width and height to maintain aspect ratio
      if (fileMBR.getWidth() / fileMBR.getHeight() > (double) width / height) {
        // Fix width and change height
        height = (int) (fileMBR.getHeight() * width / fileMBR.getWidth());
        // Make divisible by two for compatibility with ffmpeg
        height &= 0xfffffffe;
        job.setInt("height", height);
      } else {
        width = (int) (fileMBR.getWidth() * height / fileMBR.getHeight());
        job.setInt("width", width);
      }
    }
    
    if (job.getBoolean("sample", false)) {
      // Need to set the sample ratio
      int imageWidth = job.getInt("width", 1000);
      int imageHeight = job.getInt("height", 1000);
      long recordCount = FileMBR.fileMBR(inFile, params).recordCount;
      float sampleRatio = params.getFloat(GeometricPlot.AdaptiveSampleFactor, 1.0f) *
          imageWidth * imageHeight / recordCount;
      job.setFloat(GeometricPlot.AdaptiveSampleRatio, sampleRatio);
    }

    LOG.info("Creating an image of size "+width+"x"+height);
    ImageOutputFormat.setFileMBR(job, fileMBR);
    if (plotRange != null) {
      job.setClass(SpatialSite.FilterClass, RangeFilter.class, BlockFilter.class);
    }

    ShapeInputFormat.addInputPath(job, inFile);
    job.setOutputCommitter(PlotOutputCommitter.class);

    job.setOutputFormat(ImageOutputFormat.class);
    TextOutputFormat.setOutputPath(job, outFile);

    if (OperationsParams.isLocal(job, inFile)) {
      // Enforce local execution if explicitly set by user or for small files
      job.set("mapred.job.tracker", "local");
      // Use multithreading too
      job.setInt(LocalJobRunner.LOCAL_MAX_MAPS, Runtime.getRuntime().availableProcessors());
    }
    
    if (background) {
      JobClient jc = new JobClient(job);
      return lastSubmittedJob = jc.submitJob(job);
    } else {
      return lastSubmittedJob = JobClient.runJob(job);
    }
  }

  private static void printUsage() {
    System.out.println("Plots all shapes to an image");
    System.out.println("Parameters: (* marks required parameters)");
    System.out.println("<input file> - (*) Path to input file");
    System.out.println("<output file> - (*) Path to output file");
    System.out.println("shape:<point|rectangle|polygon|ogc> - (*) Type of shapes stored in input file");
    System.out.println("width:<w> - Maximum width of the image (1000)");
    System.out.println("height:<h> - Maximum height of the image (1000)");
    System.out.println("radius:<r> - Radius used when smoothing the heat map");
    System.out.println("valuerange:<min,max> - Range of values for plotting the heat map");
    System.out.println("color1:<c> - Color to use for minimum values");
    System.out.println("color2:<c> - Color to use for maximum values");
    System.out.println("gradient:<hue|color> - Method to change gradient from color1 to color2");
    System.out.println("-overwrite: Override output file without notice");
    System.out.println("-vflip: Vertically flip generated image to correct +ve Y-axis direction");
    System.out.println("-skipzeros: Leave empty areas (frequency < min) transparent");
    System.out.println("-smooth: Use mean smoothing to the result");
    
    GenericOptionsParser.printGenericCommandUsage(System.out);
  }
  
  /**
   * @param args
   * @throws IOException 
   */
  public static void main(String[] args) throws IOException {
    System.setProperty("java.awt.headless", "true");
    OperationsParams params = new OperationsParams(new GenericOptionsParser(args));
    if (!params.checkInputOutput()) {
      printUsage();
      System.exit(1);
    }
    
    Path inFile = params.getInputPath();
    Path outFile = params.getOutputPath();

    long t1 = System.currentTimeMillis();
    plotHeatMapMapReduce(inFile, outFile, params);
    long t2 = System.currentTimeMillis();
    System.out.println("Plot heat map finished in "+(t2-t1)+" millis");
  }
}