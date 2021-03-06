package com.easou.stat.app.mapreduce.client;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.db.DBOutputFormat;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.util.Tool;
import org.nutz.json.Json;

import com.alibaba.fastjson.JSONObject;
import com.easou.stat.app.constant.ActivityDimensions;
import com.easou.stat.app.constant.Constant;
import com.easou.stat.app.constant.Granularity;
import com.easou.stat.app.mapreduce.core.FirstPartitioner;
import com.easou.stat.app.mapreduce.core.OracleDBOutputFormat;
import com.easou.stat.app.mapreduce.core.TextKey;
import com.easou.stat.app.mapreduce.mrDBEntity.ReducerCollectorActivityUsers;
import com.easou.stat.app.util.CommonUtils;
import com.easou.stat.app.util.DateUtil;
import com.easou.stat.app.util.MRDriver;

/**
 * @ClassName: UsersIndicatorMapReduce2.java
 * @Description: 用户趋势分析--新增用户指标MR step-2
 * @author: Asa
 * @date: 2014年5月14日 上午10:21:50
 */
public class UsersIndicatorMapReduce2  extends Configured implements Tool {
	public static final Log LOG_MR = LogFactory.getLog(UsersIndicatorMapReduce2.class);

	@SuppressWarnings("unchecked")
	@Override
	public int run(String[] args) throws Exception {
		Map<String, String> map = Json.fromJson(Map.class, args[0]);
		map.put(Constant.APP_TMP_MAPREDUCE, "false"); // 有后续MR执行标识
		map.put(Constant.APP_MOBILEINFO_MAPREDUCE, "false"); // 客户端手机信息数据
		map.put(Constant.PHONE_BRAND, "false");
		String code = java.util.UUID.randomUUID().toString();
		map.put("任务标志", code);
		MRDriver op = new MRDriver(getConf(), Json.toJson(map), false);

		Job job = op.getJob();
		FileInputFormat.setInputPaths(job, op.getPaths());
		DBOutputFormat.setOutput(job, ReducerCollectorActivityUsers.getTableNameByGranularity(op.getGranularity()), ReducerCollectorActivityUsers.getFields());
		
		job.setMapperClass(UsersIndicator2Mapper.class);
		job.setReducerClass(UsersIndicator2Reducer.class);

		job.setPartitionerClass(FirstPartitioner.class);
		job.setGroupingComparatorClass(TextKey.GroupingComparator.class);
		
		job.setOutputKeyClass(ReducerCollectorActivityUsers.class);
		job.setOutputValueClass(NullWritable.class);
		job.setOutputFormatClass(OracleDBOutputFormat.class);
		job.setMapOutputKeyClass(TextKey.class);
		job.setMapOutputValueClass(Text.class);
		
		
		LOG_MR.info("预处理数据的MapReduce驱动配置完成!");
		LOG_MR.info("预处理数据的MapReduce任务准备提交!");

		return (job.waitForCompletion(true) ? 0 : 1);
	}
	

	public static class UsersIndicator2Mapper extends Mapper<LongWritable, Text, TextKey, Text> {
		
		private long time = 0L;
		private String granularity = null;

		@Override
		protected void map(LongWritable l, Text _line, Context context)
				throws IOException, InterruptedException {
			
			if (StringUtils.isBlank(_line.toString())) {
				return;
			}
			
			
			String dateFormat = "yyyyMMddHHmmss";
			Date date = null;
			JSONObject lineMap = null;
			try{
				lineMap = JSONObject.parseObject(_line.toString());
			}catch(Exception e){
				return;
			}
			if(lineMap == null)
				return;
			
			String peroid = "0";
			if (Granularity.HOUR.toString().equals(this.granularity) || 
					Granularity.AHOUR.toString().equals(this.granularity)) {
				date = DateUtil.getFirstMinuteOfHour(this.time);
			} else if (Granularity.DAY.toString().equals(this.granularity)) {
				date = DateUtil.getFirstHourOfDay(this.time);
				peroid = "1";
			} else if (Granularity.WEEK.toString().equals(this.granularity)) {
				date = DateUtil.getMondayOfWeek(this.time);
				peroid = "7";
			} else {
				date = DateUtil.getFirstDayOfMonth(this.time);
				peroid = "30";
			}
			String dateTime = CommonUtils.getDateTime(date.getTime(), dateFormat);
			
			String appkey = lineMap.getString("appkey");
			String version = lineMap.getString("phone_softversion");
			
			List<String> dims = composeDims(null, appkey, false);
			dims = composeDims(dims, version, true);
			dims = composeDims(dims, dateTime, false);
			dims = composeDims(dims, peroid, false);
			//channel
			String cpid = lineMap.getString("cpid");
			if(cpid == null || "".equals(cpid)){
				return;
			}
			if(cpid.length()> 24){
				cpid = cpid.substring(0, 24);
			}
			List<String> cpidDims = composeDims(dims, "cpid", cpid, true);
			writeByDim(cpidDims, lineMap, context);
			
		}
		
		private List<String> composeDims(List<String> prefixs, String dim, boolean isAll) {
			List<String> list = new ArrayList<String>();
			if(prefixs == null || prefixs.size() == 0) {
				list.add(dim);
				if(isAll)
					list.add("all");
			} else {
				for(String prefix : prefixs) {
					list.add(prefix + Constant.DELIMITER + dim);
					if(isAll)
						list.add(prefix + Constant.DELIMITER + "all");
				}
			}
			return list;
		}
		
		private List<String> composeDims(List<String> prefixs, String dim, String dimCode, boolean isAll) {
			List<String> list = new ArrayList<String>();
			dimCode = StringUtils.trimToNull(dimCode);
			if(prefixs == null || prefixs.size() == 0) {
				list.add(dim + Constant.DELIMITER + dimCode);
				if(isAll)
					list.add("all");
			} else {
				for(String prefix : prefixs) {
					if(StringUtils.isNotEmpty(dimCode)) 
						list.add(prefix + Constant.DELIMITER + dim + Constant.DELIMITER + dimCode);
					if(isAll)
						list.add(prefix + Constant.DELIMITER + dim + Constant.DELIMITER + "all");
				}
			}
			return list;
		}
		
		private void writeByDim(List<String> dims, JSONObject lineMap, Context context) throws IOException, InterruptedException {
			
			for(String dim : dims) {
				write(context, dim + Constant.DELIMITER +ActivityDimensions.newusers.toString(), lineMap.getString("phone_udid"));
			}
		}
	
		private void write(Context context, String key, String value) throws IOException, InterruptedException {
			Text textValue = new Text(value);
			TextKey textKey = new TextKey(new Text(key), textValue);
			context.write(textKey, textValue);
		}
	
		
		
		@Override
		protected void setup(Context context)
				throws IOException, InterruptedException {
			Configuration config = context.getConfiguration();
			this.granularity = config.get(Constant.GRANULARITY);
			String dateTime = config.get(Constant._DATETIME);
			SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmm");
			try {
				this.time = sdf.parse(dateTime).getTime();
			} catch (ParseException e) {
				LOG_MR.error(e);
			}
		}
	}

	public static class UsersIndicator2Reducer extends Reducer<TextKey, Text, ReducerCollectorActivityUsers, NullWritable> {
		private String jobId = null;

		protected void reduce(TextKey key, Iterable<Text> values, Context context)
				throws IOException, InterruptedException {

			String[] keyStr = key.getDimension().toString().split(Constant.DELIMITER_REG);
			String appKey = keyStr[0];
			String version = keyStr[1];
			String dateTime = keyStr[2];
			String peroid = keyStr[3];
			String dim = keyStr[4];
			String dimCode = keyStr[5];
			String indicator = keyStr[6];

			
			ReducerCollectorActivityUsers outData = new ReducerCollectorActivityUsers();
			outData.setAppkey(appKey);
			outData.setDim_type(dim);
			outData.setDim_code(dimCode);
			outData.setIndicator(indicator);
			outData.setJobid(this.jobId);
			outData.setPhone_softversion(version);
			outData.setStat_date(dateTime);
			outData.setTime_peroid(peroid);
			
			
			if(ActivityDimensions.newusers.toString().equalsIgnoreCase(indicator)) {
				double result = 0;
				int count = 0;
				String word = "";
				for(Text value : values) {
					if(!word.equals(value.toString())) {
						count ++;
						word = value.toString();
					}
				}
				result = count;
				outData.setValue(result);
				context.write(outData, NullWritable.get());
			} 
			
		}
		
		@Override
		protected void setup(Context context)
				throws IOException, InterruptedException {
			this.jobId = context.getJobID().toString();
		}
		
	}	
	
}
