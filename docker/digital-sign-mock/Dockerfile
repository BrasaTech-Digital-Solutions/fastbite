FROM node:22.14.0-alpine

WORKDIR /app
COPY server.mjs /app/server.mjs

EXPOSE 8099
USER node

CMD ["node", "/app/server.mjs"]
