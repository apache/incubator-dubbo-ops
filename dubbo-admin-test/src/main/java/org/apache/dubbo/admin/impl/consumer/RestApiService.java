/*
 *
 *   Licensed to the Apache Software Foundation (ASF) under one or more
 *   contributor license agreements.  See the NOTICE file distributed with
 *   this work for additional information regarding copyright ownership.
 *   The ASF licenses this file to You under the Apache License, Version 2.0
 *   (the "License"); you may not use this file except in compliance with
 *   the License.  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */
package org.apache.dubbo.admin.impl.consumer;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

@Path("/")
public class RestApiService {

    private static AnnotationConfigApplicationContext context;

    public RestApiService() {
        if (context == null) {
            context = new AnnotationConfigApplicationContext(ConsumerConfiguration.class);
            context.start();

            System.out.println("dubbo service init finish");
        }
    }

    @Path("/checkAlive")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public CommonResult alive() {
        return CommonResult.success("OK");
    }

    @Path("/hello")
    @GET
    @Produces(MediaType.APPLICATION_JSON) // 声明这个接口将以json格式返回
    public CommonResult hello(@QueryParam("name") String name) {
        return CommonResult.success(context.getBean(AnnotatedGreetingService.class).sayHello(name));
    }


    @Configuration
    @EnableDubbo(scanBasePackages = "org.apache.dubbo.admin.impl.consumer")
    @ComponentScan(basePackages = "org.apache.dubbo.admin.impl.consumer")
    @PropertySource("classpath:/spring/dubbo-consumer.properties")
    public static class ConsumerConfiguration {
    }

    public static class CommonResult {
        private Object data;
        private int code;

        public static CommonResult success(Object object) {
            CommonResult result = new CommonResult();
            result.data = object;
            result.code = 1;
            return result;
        }

        public Object getData() {
            return data;
        }

        public void setData(Object data) {
            this.data = data;
        }

        public int getCode() {
            return code;
        }

        public void setCode(int code) {
            this.code = code;
        }
    }
}
