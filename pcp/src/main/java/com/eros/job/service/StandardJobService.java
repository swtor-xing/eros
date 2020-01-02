package com.eros.job.service;

import com.eros.common.service.Service;
import com.eros.common.string.DoubleColumnTable;
import com.eros.common.util.LoggerUtil;
import com.eros.job.conf.JobConfig;
import com.eros.job.shared.SharedHouse;
import com.eros.job.task.Consumer;
import com.eros.job.task.Producer;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;

/**
 * Service to control produce/consume tasks.
 *
 * <P> product
 * @author Eros
 * @since   2020-01-02 15:58
 */
public class StandardJobService<P> implements Service, Runnable {

    /**
     * StoreHouse for products
     */
    private final SharedHouse<P> sharedHouse;
    /**
     * Job Configuration
     */
    private final JobConfig config;
    protected final Logger logger;
    /**
     * Producers and Consumers
     */
    private final Set<Producer<P>> producers;
    private final Set<Consumer<P>> consumers;
    /**
     * Identify as job if stopped
     */
    private volatile boolean stopped = false;
    /**
     * Identify as job if startup
     */
    private volatile boolean startUp = false;
    /**
     * log helper
     */
    private final DoubleColumnTable table;

    /**
     * Constructor
     *
     * @param config  job configuration
     */
    public StandardJobService(JobConfig config) {
        this.sharedHouse = new SharedHouse<P>();
        this.config = config;
        this.producers = Collections.newSetFromMap(new ConcurrentHashMap<>(JobConfig.TASKS_NUM_DEFAULT));
        this.consumers = Collections.newSetFromMap(new ConcurrentHashMap<>(JobConfig.TASKS_NUM_DEFAULT));
        this.logger = LoggerUtil.getLogger(config.getJobName(), this.getClass());
        Object itemKey = config.get(JobConfig.JOB_LOG_ITEM_KEY, JobConfig.JOB_LOG_ITEM_KEY_DEFAULT);
        Object itemValue = config.get(JobConfig.JOB_LOG_ITEM_VALUE, JobConfig.JOB_LOG_ITEM_VALUE_DEFAULT);
        this.table = DoubleColumnTable.newTable(itemKey.toString(), itemValue.toString());
    }

    @Override
    public String serviceName() {
        return config.getJobName();
    }

    public JobConfig getJobConfig(){
        return config;
    }

    public SharedHouse<P> getSharedHouse(){
        return sharedHouse;
    }

    @Override
    public void stop(String why) {
        if(stopped) return;
        logger.info("Stopping job: " + config.getJobName() + ", cause: " + why);
        this.stopped = true;
        logger.info("Stopping producers, size: " + producers.size());
        sharedHouse.setProducerStopped(true);
        for(Producer<P> producer : producers){
            producer.stop("stop all tasks");
        }
        logger.info("Stopping consumers, size: " + consumers.size());
        sharedHouse.setConsumerStopped(true);
        for(Consumer consumer : consumers){
            consumer.stop("stop all tasks");
        }
    }

    @Override
    public boolean isStopped() {
        return stopped;
    }

    /**
     * Register producer which can be in running job
     *
     * @param producer
     */
    public void registerProducer(Producer<P> producer) {
        if (stopped) {
            throw new RuntimeException(String.format("Can't add task:%s to job:%s stopped", producer, config.getJobName()));
        }
        producers.add(producer);
        if(startUp){
            new Thread(producer, producer.toString()).start();
        }
    }

    /**
     * Register consumer which can be in running job
     *
     * @param consumer
     */
    public void registerConsumer(Consumer<P> consumer) {
        if (stopped) {
            throw new RuntimeException(String.format("Can't add task:%s to job:%s stopped", consumer, config.getJobName()));
        }
        consumers.add(consumer);
        if(startUp){
            new Thread(consumer, consumer.toString()).start();
        }
    }


    /**
     * User to append log kv dynamically
     *
     * @return  append kvs
     */
    public Map<String, String> appendShowMSG(){
        return null;
    }

    @Override
    public void startup() {
        logger.info("Starting producers, size: " + producers.size());
        for(Producer<P> producer : producers){
            new Thread(producer, producer.toString()).start();
        }
        logger.info("Starting consumers, size: " + consumers.size());
        for(Consumer<P> consumer : consumers){
            new Thread(consumer, consumer.toString()).start();
        }
        logger.info("Starting job monitor");
        new Thread(this).start();
    }

    @Override
    public String toString() {
        return config.getJobName();
    }

    @Override
    public void run() {
        while(!stopped || !producers.isEmpty() || !consumers.isEmpty()){
            printJobInfo();
            LockSupport.parkNanos(10*1000L*1000L*1000L);

            // 监测生产者任务运行情况
            for( Iterator<Producer<P>> it = producers.iterator(); it.hasNext();){
                Producer<P> producer = it.next();
                if(producer.isStopped()){
                    logger.info("producer: " + producer + " stopped");
                    it.remove();
                }
            }
            if(producers.isEmpty() && !sharedHouse.isProducerStopped()){
                logger.info("All producer tasks stopped");
                sharedHouse.setProducerStopped(true);
            }
            // 监测消费者任务运行情况
            for( Iterator<Consumer<P>> it = consumers.iterator(); it.hasNext();){
                Consumer<P> consumer = it.next();
                if(consumer.isStopped()){
                    logger.info("consumer: " + consumer + " stopped");
                    it.remove();
                }
            }
            if(consumers.isEmpty() && !sharedHouse.isConsumerStopped()){
                logger.info("All consumer tasks stopped");
                sharedHouse.setConsumerStopped(true);
            }
            // 自动停止控制
            if(consumers.isEmpty() && producers.isEmpty()){
                stopped = true;
            }
        }
        printJobInfo();
        logger.info("Job: " + serviceName() + " monitor exiting...");
    }

    void printJobInfo(){
        Map<String, Object> logKVs = new LinkedHashMap<>();
        logKVs.put("Running producers", producers.size());
        logKVs.put("Running consumers", consumers.size());
        Map<String, String> appendKVs = appendShowMSG();
        if(appendKVs != null && !appendKVs.isEmpty()){
            logKVs.putAll(appendKVs);
        }
        logger.info(table.format(logKVs));
    }
}
