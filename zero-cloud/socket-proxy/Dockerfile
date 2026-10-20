FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
# worker 进程需 root 才能打开 /var/run/docker.sock（srw root:docker 0660）；
# 本容器是唯一的 socket 守卫，不执行不可信代码
RUN sed -i 's/^user  nginx;/user  root;/' /etc/nginx/nginx.conf
CMD ["nginx", "-g", "daemon off;"]
