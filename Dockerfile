FROM node:22-alpine

WORKDIR /app

# Instala dependências completas (server.ts usa tsx + build Vite)
COPY package.json ./
RUN npm install --no-audit --no-fund

# Copia restante do projeto e gera build de produção do frontend + PWA
COPY . .
RUN npm run build

ENV NODE_ENV=production
ENV PORT=8080

EXPOSE 8080

CMD ["npm", "start"]
