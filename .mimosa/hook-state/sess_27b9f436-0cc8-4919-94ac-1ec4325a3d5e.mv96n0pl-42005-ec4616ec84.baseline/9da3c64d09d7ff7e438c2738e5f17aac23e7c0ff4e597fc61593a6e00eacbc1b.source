package com.zhixing;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MapperScan 在契约基线一次性配好（并行开发约定）：所有 mapper 必须放 com.zhixing.dao.mapper，
 * 任何任务不得修改本注解或另配扫描路径。
 */
@SpringBootApplication
@MapperScan("com.zhixing.dao.mapper")
public class ZhixingApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZhixingApplication.class, args);
    }

}
