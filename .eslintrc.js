module.exports = {
  root: true,
  extends: ['@react-native'],
  plugins: ['prettier'],
  rules: {
    'prettier/prettier': [
      'error',
      {
        singleQuote: true,
        trailingComma: 'all',
        bracketSpacing: true,
        arrowParens: 'avoid',
      },
    ],
  },
  overrides: [
    {
      files: ['*.js'],
      parserOptions: {
        requireConfigFile: false,
      },
    },
    {
      files: ['**/__tests__/**', '**/*.test.ts'],
      env: {
        jest: true,
      },
    },
  ],
  ignorePatterns: ['lib/**', 'node_modules/**'],
};
