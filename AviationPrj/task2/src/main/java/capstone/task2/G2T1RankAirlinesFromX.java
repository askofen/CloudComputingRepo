package capstone.task2;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import kafka.serializer.StringDecoder;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.PairFlatMapFunction;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.streaming.Duration;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaPairDStream;
import org.apache.spark.streaming.api.java.JavaPairInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.kafka.KafkaUtils;

import com.google.common.base.Optional;

import scala.Tuple2;
import capstone.task2.FlightInformation.ColumnNames;

public class G2T1RankAirlinesFromX {
	public static void main(String[] args) throws IOException {
		if (args.length < 4) {
			System.exit(1);
		}

		String className = G2T1RankAirlinesFromX.class.getSimpleName();
		Map<String, String> paramsMap = new HashMap<String, String>();
		SparkConf sparkConf = new SparkConf().setAppName(className);
		Map<String, Integer> topicMap = new HashMap<String, Integer>();
		
		MapReduceHelper.fillBaseStreamingParams(args, paramsMap, sparkConf, topicMap, className);
		final String filterStr = sparkConf.get(MapReduceHelper.INPUT_PARAMS);
		
		JavaStreamingContext jssc = new JavaStreamingContext(sparkConf, new Duration(5000));
		JavaPairInputDStream<String, String> messages = KafkaUtils.createDirectStream(jssc, String.class, String.class, StringDecoder.class, StringDecoder.class, paramsMap, topicMap.keySet());	
		jssc.checkpoint("/tmp/G2T1");
		
		JavaDStream<String> lines = messages.map(MapReduceHelper.getBaseInputPreprocessingFunction());

		JavaPairDStream<String, Tuple2<Long, Integer>> sums = lines.flatMapToPair(
				new PairFlatMapFunction<String, String, Tuple2<Long, Integer>>() {
					private static final long serialVersionUID = 1L;
					ColumnNames[] columns = new ColumnNames[] { ColumnNames.Origin, ColumnNames.AirlineID, ColumnNames.DepDelayed, ColumnNames.AirlineCode };
		
					public Iterable<Tuple2<String, Tuple2<Long, Integer>>> call(String value) throws Exception {
						ArrayList<Tuple2<String, Tuple2<Long, Integer>>> list = new ArrayList<Tuple2<String, Tuple2<Long, Integer>>>();
		
			            if (value == null || value.toString().trim().isEmpty())
			            	return list;
			            
			            FlightInformation information = new FlightInformation(value.toString().trim(), columns);
			            
						String origin = information.GetValues()[0];		
						String airports = filterStr;
						
						if (origin.isEmpty() || !airports.contains(origin))
							return list;

						String depDelayed = information.GetValue(ColumnNames.DepDelayed);		
						String airlineID = information.GetValue(ColumnNames.AirlineID);		
						
						if (depDelayed.isEmpty() || airlineID.isEmpty())
							return list;
						
						Integer depDelayedVal = Integer.parseInt(depDelayed);
						list.add(new Tuple2<String, Tuple2<Long, Integer>>(origin + "_" + airlineID, new Tuple2<Long, Integer>((long)depDelayedVal, 1)));

						return list;
					}
				}).reduceByKey(new Function2<Tuple2<Long, Integer>, Tuple2<Long, Integer>, Tuple2<Long, Integer>>() {
						private static final long serialVersionUID = 1L;
						public Tuple2<Long, Integer> call(Tuple2<Long, Integer> value1, Tuple2<Long, Integer> value2) throws Exception {
							return new Tuple2<Long, Integer>(value1._1() + value2._1(), value1._2() + value2._2());
						}
		});
	
		sums = sums.updateStateByKey(new Function2<List<Tuple2<Long, Integer>>, Optional<Tuple2<Long, Integer>>, Optional<Tuple2<Long, Integer>>>() {
			private static final long serialVersionUID = 1L;
			public Optional<Tuple2<Long, Integer>> call(List<Tuple2<Long, Integer>> v1, Optional<Tuple2<Long, Integer>> v2) throws Exception {
						Long sum1 = 0l;
						Integer sum2 = 0;

						for (Tuple2<Long, Integer> i : v1) {
							sum1 += i._1();
							sum2 += i._2();
						}

						if (v2.isPresent()){
							sum1 += v2.get()._1();
							sum2 += v2.get()._2();
						}
		
						return Optional.of(new Tuple2<Long, Integer>(sum1, sum2));
					}
			});
		
		JavaPairDStream<String, Tuple2<Long, Integer>> sorted = sums.transformToPair(G2T1RankAirlinesFromX.getSortFunction());
		
		sorted.print();
		jssc.start();
		MapReduceHelper.awaitTermination(jssc);
		jssc.stop();
	}
	
	public static Function<Tuple2<String,Tuple2<Long,Integer>>, Boolean> GetFilterEmpty(){
		return new Function<Tuple2<String,Tuple2<Long,Integer>>, Boolean>(){
			private static final long serialVersionUID = 1L;
			public Boolean call(Tuple2<String, Tuple2<Long, Integer>> pair) throws Exception {
				if (pair == null || pair._1() == null || pair._1().isEmpty() || pair._2() == null)
					return false;
			
				return true;
			}
		};
	}
	
	public static Function<JavaPairRDD<String,Tuple2<Long,Integer>>, JavaPairRDD<String, Tuple2<Long, Integer>>> getSortFunction(){
		return new Function<JavaPairRDD<String,Tuple2<Long,Integer>>, JavaPairRDD<String, Tuple2<Long, Integer>>>() {
			private static final long serialVersionUID = 1L;
			private Boolean isWritten = false;
						
			public JavaPairRDD<String, Tuple2<Long, Integer>> call(JavaPairRDD<String, Tuple2<Long, Integer>> pairs) throws Exception {
				if (!isWritten && MapReduceHelper.flushRDD)
				{
					JavaPairRDD<String, Double> perfs = pairs.filter(GetFilterEmpty()).mapToPair(new PairFunction<Tuple2<String,Tuple2<Long,Integer>>, String, Double>() {
						private static final long serialVersionUID = 1L;
						public Tuple2<String, Double> call(Tuple2<String, Tuple2<Long, Integer>> pair) throws Exception {
							double sum = pair._2()._1();
				        	int valuesCount = pair._2()._2();
							
				        	double perf = 1;
				        	double inTime = valuesCount - sum;
				        	
				        	if (valuesCount != 0)
				        		perf = inTime / valuesCount;
				        	
							return new Tuple2<String, Double>(pair._1(), perf);
						}			 
					 }).reduceByKey(new Function2<Double, Double, Double>() {
							private static final long serialVersionUID = 1L;
							public Double call(Double value1, Double value2) throws Exception {
								return value1 + value2;
							}
						});
					
					JavaPairRDD<Double, String> rdd = perfs.flatMapToPair(MapReduceHelper.<String, Double>getRDDFlipFunction()).sortByKey(false);
					
	
					isWritten = true;
					System.out.println("\n-------WRITE TO CASSANDRA 1------ ");


					String cassandraIp = rdd.context().getConf().get("spark.connection.cassandra.host");
					Integer cassandraPort = Integer.parseInt(rdd.context().getConf().get("spark.cassandra.connection.port"));

					CassandraHelper cassandraHelper = new CassandraHelper();
					cassandraHelper.createConnection(cassandraIp, cassandraPort);
					
					List<Tuple2<Double, String>> list = rdd.toArray();
                	cassandraHelper.prepareQueries("INSERT INTO keyspacecapstone.topfromx (Origin, FromAirline, Perf, Id) VALUES (?,?,?,?);");

                	Object[] values = new Object[4];
                	Integer i = 0;
                	
                	Map<String, Integer> countAirports = new HashMap<String, Integer>();
                	
                    for (Tuple2<Double, String> tuple2 : list) {                   	
                    	String[] originAirline = tuple2._2().split("_");
                    	String origin = originAirline[0];
                    			
                    	if (!countAirports.containsKey(origin)) {
                    		countAirports.put(origin, 0);
                    	} 

                    	Integer countValue = countAirports.get(origin);
                    	
                    	if (10 <= countValue)
                    		continue;
                    	
                    	countAirports.put(origin, countValue + 1);
                    	
                    	values[0] = origin;
                    	values[1] = originAirline[1];
                    	values[2] = tuple2._1().toString();
                    	values[3] = i;
                    	i++;
                    	
                    	System.out.println("\n--------CASSANDRA " + tuple2._2() + " " + tuple2._1() + " " + i);
                    	
                    	cassandraHelper.addKey(values);
                    	Thread.sleep(100);
					}
                    
    				cassandraHelper.closeConnection();
				}
				
				return pairs;
			}
		};
	}
}
