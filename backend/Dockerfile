FROM node:24-alpine
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev
COPY src ./src
RUN mkdir /app/data && chown node:node /app/data
USER node
ENV PORT=8080 DATA_DIR=/app/data
EXPOSE 8080
CMD ["node", "src/server.mjs"]
