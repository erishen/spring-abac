# Next.js 前端多阶段构建。
# BACKEND_URL 在构建期写进 next.config.mjs 的 rewrite 目标（容器内走服务名，不是 localhost）。
FROM node:20-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm install --no-audit --no-fund

FROM node:20-alpine AS builder
WORKDIR /app
ARG BACKEND_URL=http://gateway-service:4110
ENV BACKEND_URL=$BACKEND_URL
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build

FROM node:20-alpine AS runner
WORKDIR /app
# Next 服务端在运行时才读 next.config.mjs 生成 rewrite 规则 —— 不拷它，
# 浏览器访问同源 /api/* 会全部 404（前端看起来能起，但一登录就废）。
ARG BACKEND_URL=http://gateway-service:4110
ENV NODE_ENV=production
ENV BACKEND_URL=$BACKEND_URL
COPY --from=builder /app/package.json ./package.json
COPY --from=builder /app/next.config.mjs ./next.config.mjs
COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/.next ./.next
EXPOSE 3001
CMD ["npm", "run", "start"]
